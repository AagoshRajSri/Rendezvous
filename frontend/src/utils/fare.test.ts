import { describe, it, expect } from 'vitest';
import { calculateFares, formatDistance, formatDuration } from './fare';

describe('fare calculation and formatting utilities', () => {
  it('calculates fares for 10 km and 20 min correctly across tiers', () => {
    const fares = calculateFares(10, 20);
    expect(fares).toHaveLength(3);

    // Rendezvous Go: base 50 + (10 * 15 = 150) + (20 * 2 = 40) = 240
    const standard = fares.find((f) => f.tierId === 'standard')!;
    expect(standard.baseFare).toBe(50);
    expect(standard.distanceFare).toBe(150);
    expect(standard.timeFare).toBe(40);
    expect(standard.totalFare).toBe(240);

    // Premier: base 80 + (10 * 22 = 220) + (20 * 3 = 60) = 360
    const premier = fares.find((f) => f.tierId === 'premier')!;
    expect(premier.baseFare).toBe(80);
    expect(premier.distanceFare).toBe(220);
    expect(premier.timeFare).toBe(60);
    expect(premier.totalFare).toBe(360);

    // XL: base 120 + (10 * 28 = 280) + (20 * 4 = 80) = 480
    const xl = fares.find((f) => f.tierId === 'xl')!;
    expect(xl.baseFare).toBe(120);
    expect(xl.distanceFare).toBe(280);
    expect(xl.timeFare).toBe(80);
    expect(xl.totalFare).toBe(480);
  });

  it('enforces minimum fare for very short or 0 km trips', () => {
    const fares = calculateFares(0.1, 1);

    const standard = fares.find((f) => f.tierId === 'standard')!;
    expect(standard.totalFare).toBe(70); // minimum fare is 70

    const premier = fares.find((f) => f.tierId === 'premier')!;
    expect(premier.totalFare).toBe(120); // minimum fare is 120

    const xl = fares.find((f) => f.tierId === 'xl')!;
    expect(xl.totalFare).toBe(180); // minimum fare is 180
  });

  it('handles zero or non-finite inputs gracefully without throwing', () => {
    const zeroFares = calculateFares(0, 0);
    expect(zeroFares[0].totalFare).toBe(70);

    const nanFares = calculateFares(NaN, Infinity);
    expect(nanFares[0].totalFare).toBe(70);
  });

  it('formats distance in meters and kilometers correctly', () => {
    expect(formatDistance(350)).toBe('350 m');
    expect(formatDistance(999)).toBe('999 m');
    expect(formatDistance(1500)).toBe('1.5 km');
    expect(formatDistance(12400)).toBe('12 km');
    expect(formatDistance(0)).toBe('0 m');
    expect(formatDistance(-5)).toBe('0 m');
  });

  it('formats duration in minutes and hours correctly', () => {
    expect(formatDuration(45)).toBe('1 min');
    expect(formatDuration(300)).toBe('5 min');
    expect(formatDuration(3600)).toBe('1 hr');
    expect(formatDuration(4500)).toBe('1 hr 15 min');
    expect(formatDuration(7200)).toBe('2 hr');
    expect(formatDuration(0)).toBe('0 min');
  });
});
