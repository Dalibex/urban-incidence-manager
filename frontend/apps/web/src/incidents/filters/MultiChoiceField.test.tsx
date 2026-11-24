import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';

import { MultiChoiceField } from './MultiChoiceField';

const CHOICES = [
  { id: 'parks', label: 'Parks', checked: false },
  { id: 'lighting', label: 'Street lighting', checked: false },
];

function field(disabled: boolean) {
  return (
    <MultiChoiceField
      label="Category"
      summary="All categories"
      choices={CHOICES}
      onToggle={() => {}}
      disabled={disabled}
    />
  );
}

describe('MultiChoiceField', () => {
  // #265: the options of a filter can fail to refresh while its list is open.
  it('closes when it becomes disabled, and stays closed when it is enabled again', async () => {
    const { rerender } = render(field(false));
    await userEvent.click(screen.getByRole('button', { name: 'Category: All categories' }));
    expect(screen.getByRole('group', { name: 'Category' })).toBeDefined();

    rerender(field(true));
    expect(screen.queryByRole('group', { name: 'Category' })).toBeNull();

    rerender(field(false));
    expect(screen.queryByRole('group', { name: 'Category' })).toBeNull();
    expect(
      screen
        .getByRole('button', { name: 'Category: All categories' })
        .getAttribute('aria-expanded'),
    ).toBe('false');
  });
});
