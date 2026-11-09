import type { ApiResult, components, LocalFile } from '@urbanpulse/api-client';
import { act, fireEvent, render, screen, userEvent, waitFor } from '@testing-library/react-native';
import type { ComponentProps } from 'react';
import { Linking } from 'react-native';

import { pickPhoto } from '../attachments/photo-picker';
import { failed, ok } from '../test-utils';
import { AddPhotosScreen } from './AddPhotosScreen';

type Attachment = components['schemas']['AttachmentResponse'];
type Props = ComponentProps<typeof AddPhotosScreen>;

const mockUploadAttachment = jest.fn<
  Promise<ApiResult<Attachment>>,
  [string, LocalFile, (fraction: number) => void]
>();

jest.mock('../attachments/photo-picker', () => ({ pickPhoto: jest.fn() }));
jest.mock('../attachments/upload-retry', () => ({
  ...jest.requireActual('../attachments/upload-retry'),
  // The automatic retries still happen, without waiting.
  RETRY_DELAYS_MS: [0, 0],
}));
jest.mock('../session/SessionContext', () => ({
  useSession: () => ({ uploadAttachment: mockUploadAttachment }),
}));

const mockedPickPhoto = jest.mocked(pickPhoto);

const INCIDENT_ID = '3f1c2a9e-7b4d-4c1a-9e2f-5d6b7a8c9d0e';
const PHOTO: LocalFile = { uri: 'file:///resized.jpg', name: 'IMG_0001.jpg', type: 'image/jpeg' };
const ATTACHMENT: Attachment = {
  id: 'c4d5e6f7-a8b9-4c0d-9e1f-2a3b4c5d6e7f',
  fileName: 'IMG_0001.jpg',
  contentType: 'image/jpeg',
  size: 412_000,
  uploadedAt: '2026-11-02T10:00:12Z',
};

/** A call that stays in flight until the test answers it. */
function deferred<T>() {
  let answer: (value: T) => void = () => undefined;
  const promise = new Promise<T>((resolve) => {
    answer = resolve;
  });
  return { promise, answer };
}

async function renderScreen() {
  await render(
    <AddPhotosScreen
      navigation={{} as Props['navigation']}
      route={{ key: 'AddPhotos', name: 'AddPhotos', params: { incidentId: INCIDENT_ID } }}
    />,
  );
}

async function pickFrom(button: 'Take a photo' | 'Choose from gallery') {
  await fireEvent.press(screen.getByRole('button', { name: button }));
  return screen.findByRole('button', { name: 'Upload photo' });
}

beforeEach(() => {
  mockUploadAttachment.mockReset();
  mockedPickPhoto.mockReset().mockResolvedValue({ kind: 'photo', photo: PHOTO });
  jest.spyOn(console, 'warn').mockImplementation(() => undefined);
});

afterEach(() => {
  jest.restoreAllMocks();
});

describe('AddPhotosScreen', () => {
  it.each([
    ['Take a photo', 'camera'],
    ['Choose from gallery', 'library'],
  ] as const)('uploads a photo from "%s" and shows it (#67 T2)', async (button, source) => {
    mockUploadAttachment.mockResolvedValue(ok(ATTACHMENT));
    await renderScreen();

    await fireEvent.press(await pickFrom(button));

    expect(await screen.findByText('Uploaded photos (1)')).toBeOnTheScreen();
    expect(mockedPickPhoto).toHaveBeenCalledWith(source);
    expect(mockUploadAttachment).toHaveBeenCalledWith(INCIDENT_ID, PHOTO, expect.any(Function));
    expect(screen.getByLabelText('Uploaded photo 1')).toBeOnTheScreen();
    expect(screen.queryByRole('button', { name: 'Upload photo' })).not.toBeOnTheScreen();
  });

  it('shows the real progress of the upload', async () => {
    const reply = deferred<ApiResult<Attachment>>();
    mockUploadAttachment.mockReturnValue(reply.promise);
    await renderScreen();

    await fireEvent.press(await pickFrom('Choose from gallery'));
    const [, , onProgress] = mockUploadAttachment.mock.lastCall ?? [];
    await act(async () => onProgress?.(0.42));

    expect(screen.getByRole('progressbar', { name: 'Upload progress' })).toHaveAccessibilityValue({
      now: 42,
    });
    expect(screen.getByText('42%')).toBeOnTheScreen();
    await act(async () => reply.answer(ok(ATTACHMENT)));
    expect(screen.queryByRole('progressbar')).not.toBeOnTheScreen();
  });

  it('sends one upload when Upload is tapped twice (#67 T16)', async () => {
    const user = userEvent.setup();
    const reply = deferred<ApiResult<Attachment>>();
    mockUploadAttachment.mockReturnValue(reply.promise);
    await renderScreen();
    const upload = await pickFrom('Choose from gallery');

    await user.press(upload);
    const uploading = await screen.findByRole('button', { name: 'Uploading…' });
    await user.press(uploading);

    expect(uploading).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Take a photo' })).toBeDisabled();
    expect(mockUploadAttachment).toHaveBeenCalledTimes(1);
    await act(async () => reply.answer(ok(ATTACHMENT)));
    expect(await screen.findByText('Uploaded photos (1)')).toBeOnTheScreen();
    expect(mockUploadAttachment).toHaveBeenCalledTimes(1);
  });

  it('retries on its own and succeeds without bothering the citizen', async () => {
    mockUploadAttachment
      .mockResolvedValueOnce(failed({ kind: 'network' }))
      .mockResolvedValueOnce(ok(ATTACHMENT));
    await renderScreen();

    await fireEvent.press(await pickFrom('Choose from gallery'));

    expect(await screen.findByText('Uploaded photos (1)')).toBeOnTheScreen();
    expect(mockUploadAttachment).toHaveBeenCalledTimes(2);
    expect(screen.queryByText(/could not be uploaded/)).not.toBeOnTheScreen();
  });

  it('says the report is saved without the photo when the retries run out, and logs it (#67 T14)', async () => {
    mockUploadAttachment.mockResolvedValue(failed({ kind: 'network' }));
    await renderScreen();

    await fireEvent.press(await pickFrom('Choose from gallery'));

    expect(await screen.findByText(/Your report is saved without it/)).toBeOnTheScreen();
    expect(mockUploadAttachment).toHaveBeenCalledTimes(3);
    expect(console.warn).toHaveBeenCalledWith('Photo upload failed', {
      incidentId: INCIDENT_ID,
      failure: { kind: 'network' },
    });
    expect(screen.queryByText(/Uploaded photos/)).not.toBeOnTheScreen();
  });

  it('uploads the same photo once more when the citizen tries again (#67 T3)', async () => {
    mockUploadAttachment.mockResolvedValue(failed({ kind: 'network' }));
    await renderScreen();
    await fireEvent.press(await pickFrom('Choose from gallery'));
    const tryAgain = await screen.findByRole('button', { name: 'Try again' });
    mockUploadAttachment.mockReset().mockResolvedValue(ok(ATTACHMENT));

    await fireEvent.press(tryAgain);

    expect(await screen.findByText('Uploaded photos (1)')).toBeOnTheScreen();
    expect(mockUploadAttachment).toHaveBeenCalledTimes(1);
    expect(mockUploadAttachment).toHaveBeenCalledWith(INCIDENT_ID, PHOTO, expect.any(Function));
    expect(screen.queryByText(/could not be uploaded/)).not.toBeOnTheScreen();
  });

  it('shows a photo the API already had only once', async () => {
    mockUploadAttachment.mockResolvedValue(ok(ATTACHMENT));
    await renderScreen();
    await fireEvent.press(await pickFrom('Choose from gallery'));
    await screen.findByText('Uploaded photos (1)');

    await fireEvent.press(await pickFrom('Choose from gallery'));

    await waitFor(() => expect(mockUploadAttachment).toHaveBeenCalledTimes(2));
    expect(await screen.findByText('Uploaded photos (1)')).toBeOnTheScreen();
  });

  it.each([
    [413, 'FILE_TOO_LARGE', /The photo is too large/],
    [422, 'VALIDATION_FAILED', /not a JPEG, PNG or WebP image/],
    [409, 'ATTACHMENT_NOT_ALLOWED', /only be added while the report is waiting for review/],
  ])('explains a %i without retrying it', async (status, code, message) => {
    mockUploadAttachment.mockResolvedValue(failed({ kind: 'problem', status, code }));
    await renderScreen();

    await fireEvent.press(await pickFrom('Choose from gallery'));

    expect(await screen.findByText(message)).toBeOnTheScreen();
    expect(mockUploadAttachment).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole('button', { name: 'Try again' })).not.toBeOnTheScreen();
  });

  it('points to the settings when the camera permission is refused', async () => {
    mockedPickPhoto.mockResolvedValue({ kind: 'denied' });
    const openSettings = jest.spyOn(Linking, 'openSettings').mockResolvedValue(undefined);
    await renderScreen();

    await fireEvent.press(screen.getByRole('button', { name: 'Take a photo' }));
    await fireEvent.press(await screen.findByRole('button', { name: 'Open settings' }));

    expect(screen.getByText(/cannot use the camera/)).toBeOnTheScreen();
    expect(openSettings).toHaveBeenCalled();
    expect(mockUploadAttachment).not.toHaveBeenCalled();
  });

  it('uploads nothing when the citizen cancels the picker', async () => {
    mockedPickPhoto.mockResolvedValue({ kind: 'cancelled' });
    await renderScreen();

    await fireEvent.press(screen.getByRole('button', { name: 'Choose from gallery' }));

    await waitFor(() => expect(mockedPickPhoto).toHaveBeenCalled());
    expect(screen.queryByRole('button', { name: 'Upload photo' })).not.toBeOnTheScreen();
    expect(mockUploadAttachment).not.toHaveBeenCalled();
  });

  it('shows an error when the photo cannot be prepared', async () => {
    jest.spyOn(console, 'error').mockImplementation(() => undefined);
    mockedPickPhoto.mockRejectedValue(new Error('decode failed'));
    await renderScreen();

    await fireEvent.press(screen.getByRole('button', { name: 'Choose from gallery' }));

    expect(await screen.findByText(/could not be prepared/)).toBeOnTheScreen();
  });
});
