import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { DispatchInspectorDrawer } from './DispatchInspectorDrawer';
import type { DispatchTraceState } from '../hooks/useDispatchTrace';

describe('DispatchInspectorDrawer', () => {
  const idleTraceState: DispatchTraceState = {
    status: 'idle',
    trace: null,
    errorMessage: null,
  };

  it('does not render when isOpen is false', () => {
    const { container } = render(
      <DispatchInspectorDrawer
        isOpen={false}
        onClose={vi.fn()}
        traceState={idleTraceState}
        onRefreshTrace={vi.fn()}
        isTraceExpanded={false}
        onToggleTraceExpand={vi.fn()}
      />
    );
    expect(container.firstChild).toBeNull();
  });

  it('renders cost function weights and interactive simulator when open', () => {
    render(
      <DispatchInspectorDrawer
        isOpen={true}
        onClose={vi.fn()}
        traceState={idleTraceState}
        onRefreshTrace={vi.fn()}
        isTraceExpanded={false}
        onToggleTraceExpand={vi.fn()}
        placeName="London, UK"
      />
    );

    expect(screen.getByText('DISPATCH ENGINE INSPECTOR')).toBeDefined();
    expect(screen.getByText('Multiobjective Cost Function')).toBeDefined();
    expect(screen.getByText('Interactive Cost Simulator')).toBeDefined();
    expect(screen.getByText('Engine Architecture')).toBeDefined();
    expect(screen.getByText('50%')).toBeDefined();
    expect(screen.getByText('30%')).toBeDefined();
    expect(screen.getByText('20%')).toBeDefined();
  });

  it('triggers onRunDiagnostic when sample diagnostic button is clicked in idle state', () => {
    const onRunDiagnostic = vi.fn();
    render(
      <DispatchInspectorDrawer
        isOpen={true}
        onClose={vi.fn()}
        traceState={idleTraceState}
        onRefreshTrace={vi.fn()}
        isTraceExpanded={false}
        onToggleTraceExpand={vi.fn()}
        onRunDiagnostic={onRunDiagnostic}
      />
    );

    const diagnosticBtn = screen.getByRole('button', { name: 'Run Sample Engine Diagnostic' });
    fireEvent.click(diagnosticBtn);
    expect(onRunDiagnostic).toHaveBeenCalledTimes(1);
  });

  it('calls onClose when return button is clicked', () => {
    const onClose = vi.fn();
    render(
      <DispatchInspectorDrawer
        isOpen={true}
        onClose={onClose}
        traceState={idleTraceState}
        onRefreshTrace={vi.fn()}
        isTraceExpanded={false}
        onToggleTraceExpand={vi.fn()}
      />
    );

    fireEvent.click(screen.getByRole('button', { name: '← Return to Ride Booking' }));
    expect(onClose).toHaveBeenCalledTimes(1);
  });
});
