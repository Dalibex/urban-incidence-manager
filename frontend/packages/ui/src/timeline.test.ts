import { describe, expect, it } from 'vitest';

import {
  buildTimeline,
  type TimelineClassificationChange,
  type TimelineComment,
  type TimelineStatusChange,
} from './timeline';

const OPERATOR = { name: 'Lucía Martín', role: 'MUNICIPAL_OPERATOR' as const };

const REPORTED: TimelineStatusChange = {
  id: 'a1',
  instant: '2026-11-02T09:00:00Z',
  previousStatus: null,
  newStatus: 'REPORTED',
  reason: null,
};

const VALIDATED: TimelineStatusChange = {
  id: 'b2',
  instant: '2026-11-02T11:00:00Z',
  previousStatus: 'REPORTED',
  newStatus: 'VALIDATED',
  reason: 'Checked on the map.',
  actor: OPERATOR,
  visibleToCitizen: false,
};

const CLASSIFIED: TimelineClassificationChange = {
  id: 'c3',
  instant: '2026-11-02T10:00:00Z',
  actor: { name: 'Lucía Martín' },
  before: { category: null, priority: null, priorityReason: null },
  after: { category: { name: 'Street lighting' }, priority: 'HIGH', priorityReason: 'School.' },
};

const COMMENTED: TimelineComment = {
  id: 'd4',
  createdAt: '2026-11-02T10:30:00Z',
  author: { name: 'Pablo Ruiz', role: 'TECHNICIAN' },
  text: 'The access is through the back street.',
};

describe('buildTimeline', () => {
  it('names each status change after its event and keeps what the caller may see', () => {
    const [reported, validated] = buildTimeline([REPORTED, VALIDATED]);

    expect(reported).toMatchObject({
      kind: 'status',
      title: 'Reported',
      previousStatus: null,
      status: 'REPORTED',
      actor: null,
      internal: false,
    });
    expect(validated).toMatchObject({
      title: 'Validated',
      actor: OPERATOR,
      reason: 'Checked on the map.',
      internal: true,
    });
  });

  it('shows a reassignment and an information request as events of their own (#170 T8)', () => {
    const entries = buildTimeline([
      { ...REPORTED, id: 'x', previousStatus: 'REPORTED', newStatus: 'REPORTED' },
      { ...REPORTED, id: 'y', previousStatus: 'ASSIGNED', newStatus: 'ASSIGNED' },
    ]);

    expect(entries.map((entry) => entry.title)).toEqual(['Information requested', 'Reassigned']);
  });

  it('places the classification changes among the status changes by instant (#170 T13)', () => {
    const entries = buildTimeline([REPORTED, VALIDATED], [CLASSIFIED]);

    expect(entries.map((entry) => entry.key)).toEqual([
      'status-a1',
      'classification-c3',
      'status-b2',
    ]);
    expect(entries[1]).toMatchObject({
      kind: 'classification',
      title: 'Classification changed',
      actor: { name: 'Lucía Martín' },
      changes: [
        { field: 'category', before: null, after: 'Street lighting' },
        { field: 'priority', before: null, after: 'HIGH' },
        { field: 'priorityReason', before: null, after: 'School.' },
      ],
    });
  });

  it('places the comments among the changes by instant, with their author and text (#50 T1)', () => {
    const entries = buildTimeline([REPORTED, VALIDATED], [CLASSIFIED], [COMMENTED]);

    expect(entries.map((entry) => entry.key)).toEqual([
      'status-a1',
      'classification-c3',
      'comment-d4',
      'status-b2',
    ]);
    expect(entries[2]).toEqual({
      kind: 'comment',
      key: 'comment-d4',
      instant: '2026-11-02T10:30:00Z',
      title: 'Comment',
      actor: { name: 'Pablo Ruiz', role: 'TECHNICIAN' },
      text: 'The access is through the back street.',
    });
  });

  it('lists only the values a classification change replaced', () => {
    const [entry] = buildTimeline(
      [],
      [
        {
          ...CLASSIFIED,
          before: { priority: 'HIGH', priorityReason: 'School.' },
          after: { priority: 'LOW', priorityReason: 'School.' },
        },
      ],
    );

    expect(entry).toMatchObject({
      changes: [{ field: 'priority', before: 'HIGH', after: 'LOW' }],
    });
  });

  it('keeps one order for entries of the same instant, whatever order they arrive in (#170 T11)', () => {
    const instant = '2026-11-02T12:00:00Z';
    const first = { ...VALIDATED, id: 'p', instant };
    const second = { ...VALIDATED, id: 'q', instant };
    const classified = { ...CLASSIFIED, id: 'r', instant };
    const commented = { ...COMMENTED, id: 's', createdAt: instant };

    const once = buildTimeline([second, first], [classified], [commented]).map(
      (entry) => entry.key,
    );
    const again = buildTimeline([first, second], [classified], [commented]).map(
      (entry) => entry.key,
    );

    expect(once).toEqual(['status-p', 'status-q', 'classification-r', 'comment-s']);
    expect(again).toEqual(once);
  });

  it('compares instants as times, not as text', () => {
    const entries = buildTimeline([
      { ...VALIDATED, id: 'late', instant: '2026-11-02T10:00:00.5Z' },
      { ...REPORTED, id: 'early', instant: '2026-11-02T10:00:00Z' },
    ]);

    expect(entries.map((entry) => entry.key)).toEqual(['status-early', 'status-late']);
  });
});
