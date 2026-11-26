/**
 * The file of a photo as a `data:` URI, which React Native's `Image` shows. React Native has no
 * `URL.createObjectURL`, and a download needs the session's token, so the image cannot load from a
 * URL either.
 */
export function blobToDataUri(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => {
      if (typeof reader.result === 'string') {
        resolve(reader.result);
      } else {
        reject(new Error('The file could not be read as a data URI'));
      }
    };
    reader.onerror = () => reject(reader.error ?? new Error('The file could not be read'));
    reader.readAsDataURL(blob);
  });
}
