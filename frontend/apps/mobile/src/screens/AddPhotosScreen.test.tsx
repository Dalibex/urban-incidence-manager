import {
  getAttachmentContent,
  getIncident,
  listIncidentAttachments,
  listIncidentStatusChanges,
  type ApiResult,
  type components,
  type LocalFile,
  type SessionMark,
} from '@urbanpulse/api-client';
import { act, fireEvent, screen, userEvent, waitFor } from '@testing-library/react-native';
import type { ComponentProps } from 'react';
import { Linking } from 'react-native';

import { pickPhoto } from '../attachments/photo-picker';
import { failed, ok, renderWithQueries } from '../test-utils';
import { AddPhotosScreen } from './AddPhotosScreen';
import { IncidentDetailScreen } from './IncidentDetailScreen';

type Attachment = components['schemas']['AttachmentResponse'];
type Props = ComponentProps<typeof AddPhotosScreen>;

const mockUploadAttachment = jest.fn<
  Promise<ApiResult<Attachment>>,
  [string, LocalFile, (fraction: number) => void, SessionMark | undefined]
>();
// The screen only passes the mark on; what it means is tested in api-client.
const SESSION = { sessionEnds: 0 } as unknown as SessionMark;
const mockMarkSession = jest.fn<SessionMark, []>();

// Only the calls of the detail that shares the cache with the screen; the upload is mocked below.
jest.mock('@urbanpulse/api-client', () => ({
  getAttachmentContent: jest.fn(),
  getIncident: jest.fn(),
  listIncidentAttachments: jest.fn(),
  listIncidentStatusChanges: jest.fn(),
}));
// A Blob cannot be read here; what the conversion does is tested in its own file.
jest.mock('../attachments/blob-uri', () => ({
  blobToDataUri: jest.fn(async () => 'data:image/jpeg;base64,AAAA'),
}));
jest.mock('../attachments/photo-picker', () => ({ pickPhoto: jest.fn() }));
jest.mock('../attachments/upload-retry', () => ({
  ...jest.requireActual('../attachments/upload-retry'),
  // The automatic retries still happen, without waiting.
  RETRY_DELAYS_MS: [0, 0],
}));
jest.mock('../session/SessionContext', () => ({
  useSession: () => ({
    api: {},
    uploadAttachment: mockUploadAttachment,
    markSession: mockMarkSession,
  }),
}));

const mockedPickPhoto = jest.mocked(pickPhoto);
const mockedGetIncident = jest.mocked(getIncident);
const mockedListAttachments = jest.mocked(listIncidentAttachments);

const INCIDENT_ID = '3f1c2a9e-7b4d-4c1a-9e2f-5d6b7a8c9d0e';
const PHOTO: LocalFile = { uri: 'file:///resized.jpg', name: 'IMG_0001.jpg', type: 'image/jpeg' };
const ATTACHMENT: Attachment = {
  id: 'c4d5e6f7-a8b9-4c0d-9e1f-2a3b4c5d6e7f',
  fileName: 'IMG_0001.jpg',
  contentType: 'image/jpeg',
  size: 412_000,
  uploadedAt: '2026-11-02T10:00:12Z',
};

// The report the detail shows; past REPORTED, so it offers no "Add photos" button of its own.
const INCIDENT: components['schemas']['IncidentResponse'] = {
  id: INCIDENT_ID,
  title: 'Street light off',
  description: 'The light has been off for three nights.',
  status: 'VALIDATED',
  category: null,
  location: {
    latitude: 36.721,
    longitude: -4.4208,
    accuracy: 8,
    address: 'Calle Larios 12',
    addressStatus: 'AVAILABLE',
    addressSource: null,
    neighborhood: 'Centro Histórico',
    district: 'Centro',
  },
  reportedAt: '2026-11-02T09:58:31Z',
  updatedAt: '2026-11-02T09:58:31Z',
  closedAt: null,
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
  return renderWithQueries(
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
  mockMarkSession.mockReset().mockReturnValue(SESSION);
  mockedPickPhoto.mockReset().mockResolvedValue({ kind: 'photo', photo: PHOTO });
  mockedGetIncident.mockReset().mockResolvedValue(ok(INCIDENT));
  jest.mocked(listIncidentStatusChanges).mockReset().mockResolvedValue(ok([]));
  mockedListAttachments.mockReset().mockResolvedValue(ok([]));
  jest
    .mocked(getAttachmentContent)
    .mockReset()
    .mockResolvedValue(ok(new Blob(['x'])));
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
    expect(mockUploadAttachment).toHaveBeenCalledWith(
      INCIDENT_ID,
      PHOTO,
      expect.any(Function),
      SESSION,
    );
    expect(screen.getByLabelText('Uploaded photo 1')).toBeOnTheScreen();
    expect(screen.queryByRole('button', { name: 'Upload photo' })).not.toBeOnTheScreen();
  });

  // #279, T5: the detail and the list show the new photo without a manual refresh.
  describe('with the detail of the report on the same cache (#279 T5)', () => {
    async function renderWithDetail() {
      await renderWithQueries(
        <>
          <IncidentDetailScreen
            navigation={{} as ComponentProps<typeof IncidentDetailScreen>['navigation']}
            route={{
              key: 'ReportDetail',
              name: 'ReportDetail',
              params: { incidentId: INCIDENT_ID },
            }}
          />
          <AddPhotosScreen
            navigation={{} as Props['navigation']}
            route={{ key: 'AddPhotos', name: 'AddPhotos', params: { incidentId: INCIDENT_ID } }}
          />
        </>,
      );
      await screen.findByText('History');
      await waitFor(() => expect(mockedListAttachments).toHaveBeenCalledTimes(1));
    }

    it('shows the uploaded photo in the detail', async () => {
      mockUploadAttachment.mockResolvedValue(ok(ATTACHMENT));
      await renderWithDetail();
      expect(screen.queryByLabelText('Photo 1 of 1')).not.toBeOnTheScreen();
      mockedListAttachments.mockResolvedValue(ok([ATTACHMENT]));

      await fireEvent.press(await pickFrom('Choose from gallery'));

      expect(await screen.findByLabelText('Photo 1 of 1')).toBeOnTheScreen();
    });

    it('asks for the photos again only after an upload that worked', async () => {
      mockUploadAttachment
        .mockResolvedValueOnce(failed({ kind: 'problem', status: 422, code: 'VALIDATION_FAILED' }))
        .mockResolvedValueOnce(ok(ATTACHMENT));
      await renderWithDetail();

      await fireEvent.press(await pickFrom('Choose from gallery'));
      await screen.findByText(/not a JPEG, PNG or WebP image/);
      expect(mockedListAttachments).toHaveBeenCalledTimes(1);

      mockedListAttachments.mockResolvedValue(ok([ATTACHMENT]));
      await fireEvent.press(screen.getByRole('button', { name: 'Upload photo' }));

      expect(await screen.findByLabelText('Photo 1 of 1')).toBeOnTheScreen();
      expect(mockedListAttachments).toHaveBeenCalledTimes(2);
    });
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

  // #259: no retry is sent with the session of someone who signed in during the wait.
  it('passes the session of the first attempt to every retry', async () => {
    mockUploadAttachment
      .mockResolvedValueOnce(failed({ kind: 'network' }))
      .mockResolvedValueOnce(ok(ATTACHMENT));
    await renderScreen();

    await fireEvent.press(await pickFrom('Choose from gallery'));

    expect(await screen.findByText('Uploaded photos (1)')).toBeOnTheScreen();
    expect(mockMarkSession).toHaveBeenCalledTimes(1);
    expect(mockUploadAttachment.mock.calls.map((call) => call[3])).toEqual([SESSION, SESSION]);
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
    expect(mockUploadAttachment).toHaveBeenCalledWith(
      INCIDENT_ID,
      PHOTO,
      expect.any(Function),
      SESSION,
    );
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
