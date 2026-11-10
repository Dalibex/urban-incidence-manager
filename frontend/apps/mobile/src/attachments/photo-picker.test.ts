import { ImageManipulator } from 'expo-image-manipulator';
import * as ImagePicker from 'expo-image-picker';

import { MAX_PHOTO_SIDE, PHOTO_QUALITY, pickPhoto } from './photo-picker';

jest.mock('expo-image-picker', () => ({
  requestCameraPermissionsAsync: jest.fn(),
  launchCameraAsync: jest.fn(),
  launchImageLibraryAsync: jest.fn(),
}));
jest.mock('expo-image-manipulator', () => ({
  ImageManipulator: { manipulate: jest.fn() },
  SaveFormat: { JPEG: 'jpeg' },
}));

const mockedPicker = jest.mocked(ImagePicker);
const mockedManipulate = jest.mocked(ImageManipulator.manipulate);

const ASSET = {
  uri: 'file:///DCIM/IMG_0001.HEIC',
  width: 4032,
  height: 3024,
  fileName: 'IMG_0001.HEIC',
} as ImagePicker.ImagePickerAsset;

/** A decoded image of the given size; saving it gives `file:///resized.jpg`. */
function image(width: number, height: number) {
  return {
    width,
    height,
    saveAsync: jest.fn(async () => ({ uri: 'file:///resized.jpg', width, height })),
  };
}

/** Each `manipulate` call renders the next image. */
function rendering(...images: ReturnType<typeof image>[]) {
  const contexts = images.map((rendered) => {
    const context = { resize: jest.fn(), renderAsync: jest.fn(async () => rendered) };
    context.resize.mockReturnValue(context);
    return context;
  });
  for (const context of contexts) {
    mockedManipulate.mockReturnValueOnce(context as never);
  }
  return contexts;
}

function picked(asset: ImagePicker.ImagePickerAsset = ASSET): ImagePicker.ImagePickerResult {
  return { canceled: false, assets: [asset] };
}

beforeEach(() => {
  jest.resetAllMocks();
  mockedPicker.requestCameraPermissionsAsync.mockResolvedValue({
    granted: true,
  } as ImagePicker.CameraPermissionResponse);
});

describe('pickPhoto', () => {
  it('does not open the camera when its permission is refused', async () => {
    mockedPicker.requestCameraPermissionsAsync.mockResolvedValue({
      granted: false,
    } as ImagePicker.CameraPermissionResponse);

    await expect(pickPhoto('camera')).resolves.toEqual({ kind: 'denied' });
    expect(mockedPicker.launchCameraAsync).not.toHaveBeenCalled();
  });

  it('opens the gallery without asking for a permission', async () => {
    mockedPicker.launchImageLibraryAsync.mockResolvedValue({ canceled: true, assets: null });

    await expect(pickPhoto('library')).resolves.toEqual({ kind: 'cancelled' });
    expect(mockedPicker.requestCameraPermissionsAsync).not.toHaveBeenCalled();
    expect(mockedPicker.launchImageLibraryAsync).toHaveBeenCalledWith(
      expect.objectContaining({ mediaTypes: ['images'] }),
    );
  });

  it('resizes a large photo to the longest side and saves it as JPEG', async () => {
    mockedPicker.launchCameraAsync.mockResolvedValue(picked());
    const resized = image(MAX_PHOTO_SIDE, 1440);
    const [, resize] = rendering(image(4032, 3024), resized);

    const outcome = await pickPhoto('camera');

    expect(resize?.resize).toHaveBeenCalledWith({ width: MAX_PHOTO_SIDE });
    expect(resized.saveAsync).toHaveBeenCalledWith({ compress: PHOTO_QUALITY, format: 'jpeg' });
    expect(outcome).toEqual({
      kind: 'photo',
      photo: { uri: 'file:///resized.jpg', name: 'IMG_0001.jpg', type: 'image/jpeg' },
    });
  });

  it('limits the height of a portrait photo', async () => {
    mockedPicker.launchCameraAsync.mockResolvedValue(picked());
    const [, resize] = rendering(image(3024, 4032), image(1440, MAX_PHOTO_SIDE));

    await pickPhoto('camera');

    expect(resize?.resize).toHaveBeenCalledWith({ height: MAX_PHOTO_SIDE });
  });

  it('only compresses a photo that is already small enough', async () => {
    mockedPicker.launchImageLibraryAsync.mockResolvedValue(
      picked({ ...ASSET, fileName: null } as ImagePicker.ImagePickerAsset),
    );
    const small = image(1200, 900);
    rendering(small);

    const outcome = await pickPhoto('library');

    expect(mockedManipulate).toHaveBeenCalledTimes(1);
    expect(small.saveAsync).toHaveBeenCalled();
    expect(outcome).toEqual({
      kind: 'photo',
      photo: { uri: 'file:///resized.jpg', name: 'photo.jpg', type: 'image/jpeg' },
    });
  });
});
