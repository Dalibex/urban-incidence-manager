import { render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { ChartTable } from './ChartTable';
import { NOT_CLASSIFIED, type DashboardChart } from './dashboard-data';
import { MapKey } from './MapKey';

const CATEGORIES = [
  { id: 'c1', name: 'Street lighting', description: null, icon: 'STREET_LIGHTING' as const },
  { id: 'c2', name: 'Other', description: null, icon: null },
];

/** The list under a title of the key. */
function keySection(title: string): HTMLElement {
  return screen.getByText(title).nextElementSibling as HTMLElement;
}

// #267: the icon never carries the category alone; it is hidden and the name is in words.
describe('the category icons of the map key', () => {
  it('lists each category by name with its icon, then the incidents without category', () => {
    render(<MapKey categories={CATEGORIES} open onToggle={() => {}} />);

    const items = within(keySection('Icon: category')).getAllByRole('listitem');
    expect(items.map((item) => item.textContent)).toEqual([
      'Street lighting',
      'Other',
      NOT_CLASSIFIED,
    ]);
    for (const item of items) {
      expect(item.querySelector('svg')?.getAttribute('aria-hidden')).toBe('true');
    }
  });

  it('still names the incidents without category before the categories load', () => {
    render(<MapKey open onToggle={() => {}} />);

    const items = within(keySection('Icon: category')).getAllByRole('listitem');
    expect(items.map((item) => item.textContent)).toEqual([NOT_CLASSIFIED]);
  });
});

describe('the table of a chart that cannot be drawn', () => {
  it('shows the icon of each category bar beside its name, hidden from screen readers', () => {
    const chart: DashboardChart = {
      id: 'category',
      title: 'By category',
      bars: [
        { label: 'Street lighting', count: 3, color: '#2563EB', icon: 'STREET_LIGHTING' },
        { label: NOT_CLASSIFIED, count: 1, color: '#64748B', icon: 'GENERIC' },
      ],
    };

    render(<ChartTable chart={chart} />);

    const names = screen.getAllByRole('rowheader');
    expect(names.map((name) => name.textContent)).toEqual(['Street lighting', NOT_CLASSIFIED]);
    for (const name of names) {
      expect(name.querySelector('svg')?.getAttribute('aria-hidden')).toBe('true');
    }
  });
});
