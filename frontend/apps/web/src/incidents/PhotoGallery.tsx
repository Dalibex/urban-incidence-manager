import type { components } from '@urbanpulse/api-client';
import { useCallback } from 'react';

import { useAttachmentContent, useIncidentAttachments } from './incident-queries';
import styles from './Incidents.module.css';
import { LoadFailure } from './LoadFailure';

type Attachment = components['schemas']['AttachmentResponse'];

/**
 * The photos of an incident, for the staff who may see them (#194). Each photo loads on its own, so
 * one that fails leaves the others on screen (#67, T18).
 */
export function PhotoGallery({ incidentId }: { incidentId: string }) {
  const attachments = useIncidentAttachments(incidentId);
  return (
    <section className={styles.panel} aria-labelledby="photos-title">
      <h2 id="photos-title" className={styles.panelTitle}>
        Photos
      </h2>
      {attachments.isPending ? (
        <p className={styles.muted} role="status">
          Loading the photos…
        </p>
      ) : attachments.isError ? (
        <LoadFailure
          what="The photos"
          error={attachments.error}
          retrying={attachments.isFetching}
          onRetry={() => void attachments.refetch()}
        />
      ) : attachments.data.length === 0 ? (
        <p className={styles.muted}>No photos were attached.</p>
      ) : (
        <ul className={styles.gallery}>
          {attachments.data.map((attachment) => (
            <li key={attachment.id}>
              <Photo attachment={attachment} />
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

function Photo({ attachment }: { attachment: Attachment }) {
  const content = useAttachmentContent(attachment.id);
  return (
    <figure className={styles.photo}>
      {content.isPending ? (
        <div className={styles.photoFrame} role="status">
          Loading the photo…
        </div>
      ) : content.isError ? (
        <div className={`${styles.photoFrame} ${styles.photoFailure}`} role="alert">
          <p>This photo could not be loaded.</p>
          <button
            type="button"
            className={styles.button}
            onClick={() => void content.refetch()}
            disabled={content.isFetching}
          >
            {content.isFetching ? 'Trying again…' : 'Try again'}
          </button>
        </div>
      ) : (
        <BlobImage blob={content.data} alt={attachment.fileName} />
      )}
      <figcaption className={styles.muted}>{attachment.fileName}</figcaption>
    </figure>
  );
}

/**
 * Shows a downloaded file through an object URL that lives as long as the image: React runs the
 * cleanup of the ref when the image goes away or the file changes, and the URL is released.
 */
function BlobImage({ blob, alt }: { blob: Blob; alt: string }) {
  const showBlob = useCallback(
    (image: HTMLImageElement | null) => {
      if (!image) {
        return;
      }
      const url = URL.createObjectURL(blob);
      image.src = url;
      return () => URL.revokeObjectURL(url);
    },
    [blob],
  );
  return <img ref={showBlob} className={styles.photoFrame} alt={alt} />;
}
