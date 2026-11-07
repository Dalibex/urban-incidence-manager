import type { LocalFile } from '@urbanpulse/api-client';
import { ImageManipulator, SaveFormat } from 'expo-image-manipulator';
import * as ImagePicker from 'expo-image-picker';

/** Longest side of an uploaded photo: enough to see the problem, far below the size limit. */
export const MAX_PHOTO_SIDE = 1920;

/** JPEG quality of the uploaded photo, from 0 to 1. */
export const PHOTO_QUALITY = 0.8;

export type PhotoSource = 'camera' | 'library';

export type PickOutcome =
  | { kind: 'photo'; photo: LocalFile }
  | { kind: 'cancelled' }
  /** The camera permission was refused; only the system settings can grant it now. */
  | { kind: 'denied' };

const PICKER_OPTIONS: ImagePicker.ImagePickerOptions = {
  mediaTypes: ['images'],
  // The original goes to the resize step, which compresses once.
  quality: 1,
  exif: false,
};

/**
 * One photo from the camera or the gallery, resized for upload. The camera asks for its permission
 * only now, when the citizen chose it; the gallery opens the system picker, which needs none.
 */
export async function pickPhoto(source: PhotoSource): Promise<PickOutcome> {
  if (source === 'camera') {
    const permission = await ImagePicker.requestCameraPermissionsAsync();
    if (!permission.granted) {
      return { kind: 'denied' };
    }
  }
  const result =
    source === 'camera'
      ? await ImagePicker.launchCameraAsync(PICKER_OPTIONS)
      : await ImagePicker.launchImageLibraryAsync(PICKER_OPTIONS);
  const asset = result.canceled ? undefined : result.assets[0];
  if (!asset) {
    return { kind: 'cancelled' };
  }
  return { kind: 'photo', photo: await resizeForUpload(asset) };
}

/**
 * A JPEG whose longest side is at most {@link MAX_PHOTO_SIDE}. The size comes from the decoded
 * image, because the picker may report 0 when the system does not give it.
 */
export async function resizeForUpload(asset: ImagePicker.ImagePickerAsset): Promise<LocalFile> {
  const original = await ImageManipulator.manipulate(asset.uri).renderAsync();
  const { width, height } = original;
  const image =
    Math.max(width, height) > MAX_PHOTO_SIDE
      ? await ImageManipulator.manipulate(original)
          .resize(width >= height ? { width: MAX_PHOTO_SIDE } : { height: MAX_PHOTO_SIDE })
          .renderAsync()
      : original;
  const saved = await image.saveAsync({ compress: PHOTO_QUALITY, format: SaveFormat.JPEG });
  return { uri: saved.uri, name: jpegName(asset.fileName), type: 'image/jpeg' };
}

/** The name shown to staff; the server never uses it as a path. */
function jpegName(fileName: string | null | undefined): string {
  if (!fileName) {
    return 'photo.jpg';
  }
  const dot = fileName.lastIndexOf('.');
  return `${dot > 0 ? fileName.slice(0, dot) : fileName}.jpg`;
}
