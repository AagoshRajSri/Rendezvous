import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import './index.css';
import App from './App';

// Filter benign vector tile style expression warnings from remote OpenFreeMap Liberty style
const origWarn = console.warn;
console.warn = (...args: unknown[]) => {
  const msg = args.map((a) => (typeof a === 'string' ? a : (a ? String(a) : ''))).join(' ');
  if (
    msg.includes('highway-shield') ||
    msg.includes('road_shield') ||
    msg.includes('Expected value to be of type number, but found null instead')
  ) {
    return;
  }
  origWarn.apply(console, args);
};

const rootEl = document.getElementById('root');
if (!rootEl) throw new Error('Root element #root not found');

createRoot(rootEl).render(
  <StrictMode>
    <App />
  </StrictMode>,
);

