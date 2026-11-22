import { render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { GENERIC_ICON, type IconKey } from './category-icons';
import { CategoryIconGlyph } from './CategoryIconGlyph';
import { ChartTable } from './ChartTable';
import { NOT_CLASSIFIED, type DashboardChart } from './dashboard-data';
import { MapKey } from './MapKey';

const CATEGORIES = [
  { id: 'c1', name: 'Street lighting', description: null, icon: 'STREET_LIGHTING' as const },
  { id: 'c2', name: 'Other', description: null, icon: null },
];

/** The shapes an element draws, to tell one icon from another. */
function drawingOf(element: Element): string {
  return element.querySelector('svg')!.innerHTML;
}

/** A glyph rendered apart, outside the key, as the expected drawing. */
function glyph(icon: IconKey): HTMLElement {
  return render(<CategoryIconGlyph icon={icon} />).container;
}

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
    expect(drawingOf(items[0]!)).toBe(drawingOf(glyph('STREET_LIGHTING')));
    expect(drawingOf(items[1]!)).toBe(drawingOf(glyph(GENERIC_ICON)));
    expect(drawingOf(items[0]!)).not.toBe(drawingOf(items[1]!));
  });

  // Until the categories load (or if they fail), every marker shows the generic icon, so the key
  // says so instead of calling all of them not classified.
  it('says the generic icon stands for any category until the categories load', () => {
    render(<MapKey open onToggle={() => {}} />);

    const items = within(keySection('Icon: category')).getAllByRole('listitem');
    expect(items.map((item) => item.textContent)).toEqual([
      'Not classified, or any category until they load',
    ]);
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
