import { describe, expect, it } from 'vitest';
import { hashString, mulberry32 } from './seededRandom';

describe('seededRandom', () => {
  describe('mulberry32', () => {
    it('produces identical sequences for the same seed', () => {
      const rand1 = mulberry32(123456);
      const rand2 = mulberry32(123456);

      const seq1 = [rand1(), rand1(), rand1(), rand1(), rand1()];
      const seq2 = [rand2(), rand2(), rand2(), rand2(), rand2()];

      expect(seq1).toEqual(seq2);
    });

    it('produces different sequences for different seeds', () => {
      const rand1 = mulberry32(11111);
      const rand2 = mulberry32(22222);

      const val1 = rand1();
      const val2 = rand2();

      expect(val1).not.toBe(val2);
    });

    it('generates values strictly in the range [0, 1)', () => {
      const rand = mulberry32(98765);
      for (let i = 0; i < 100; i++) {
        const val = rand();
        expect(val).toBeGreaterThanOrEqual(0);
        expect(val).toBeLessThan(1);
      }
    });
  });

  describe('hashString', () => {
    it('produces consistent non-negative 32-bit hash for identical strings', () => {
      const h1 = hashString('place-london-uk');
      const h2 = hashString('place-london-uk');

      expect(h1).toBe(h2);
      expect(h1).toBeGreaterThanOrEqual(0);
      expect(Number.isInteger(h1)).toBe(true);
    });

    it('produces different hashes for different strings', () => {
      const h1 = hashString('place-london');
      const h2 = hashString('place-paris');

      expect(h1).not.toBe(h2);
    });

    it('handles empty strings gracefully', () => {
      const h = hashString('');
      expect(typeof h).toBe('number');
      expect(h).toBeGreaterThanOrEqual(0);
    });
  });
});
