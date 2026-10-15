import { describe, expect, it } from 'vitest';
import type { PlaceSuggestion } from '../types/place';
import {
  deriveGenerationBounds,
  generateDrivers,
  isValidCoordinate,
  MAX_LAT_SPAN_DEG,
  MAX_LON_SPAN_DEG,
  synthesizeLocalBounds,
} from './driverUtils';

describe('driverUtils', () => {
  describe('isValidCoordinate', () => {
    it('accepts valid coordinates', () => {
      expect(isValidCoordinate(0, 0)).toBe(true);
      expect(isValidCoordinate(-180, -90)).toBe(true);
      expect(isValidCoordinate(180, 90)).toBe(true);
      expect(isValidCoordinate(2.3522, 48.8566)).toBe(true);
    });

    it('rejects out-of-range coordinates', () => {
      expect(isValidCoordinate(-181, 0)).toBe(false);
      expect(isValidCoordinate(181, 0)).toBe(false);
      expect(isValidCoordinate(0, -91)).toBe(false);
      expect(isValidCoordinate(0, 91)).toBe(false);
    });

    it('rejects NaN and non-finite values', () => {
      expect(isValidCoordinate(NaN, 0)).toBe(false);
      expect(isValidCoordinate(0, NaN)).toBe(false);
      expect(isValidCoordinate(Infinity, 0)).toBe(false);
      expect(isValidCoordinate(0, -Infinity)).toBe(false);
    });
  });

  describe('synthesizeLocalBounds', () => {
    it('synthesizes symmetric bounds around equator center', () => {
      const bounds = synthesizeLocalBounds(0, 0, 10);
      expect(bounds.south).toBeLessThan(0);
      expect(bounds.north).toBeGreaterThan(0);
      expect(bounds.west).toBeLessThan(0);
      expect(bounds.east).toBeGreaterThan(0);
      expect(bounds.crossesAntimeridian).toBe(false);
    });

    it('widens longitude span at high latitude to maintain physical distance', () => {
      const equatorialBounds = synthesizeLocalBounds(0, 0, 10);
      const highLatBounds = synthesizeLocalBounds(0, 60, 10);

      // cos(60°) = 0.5, so lon span should be approximately double equatorial lon span
      expect(highLatBounds.lonSpan).toBeGreaterThan(equatorialBounds.lonSpan * 1.5);
    });

    it('clamps latitude safely near poles', () => {
      const northPoleBounds = synthesizeLocalBounds(0, 89.5, 10);
      expect(northPoleBounds.north).toBeLessThanOrEqual(90);
      expect(northPoleBounds.south).toBeGreaterThan(80);

      const southPoleBounds = synthesizeLocalBounds(0, -89.5, 10);
      expect(southPoleBounds.south).toBeGreaterThanOrEqual(-90);
    });

    it('handles antimeridian crossing gracefully near ±180°', () => {
      const bounds = synthesizeLocalBounds(179.95, 0, 10);
      expect(bounds.crossesAntimeridian).toBe(true);
      expect(bounds.west).toBeGreaterThan(170);
      expect(bounds.east).toBeLessThan(-170);
    });
  });

  describe('deriveGenerationBounds', () => {
    it('preserves valid city-scale bounding box', () => {
      const place: PlaceSuggestion = {
        id: 'paris-1',
        name: 'Paris',
        subtitle: 'France',
        type: 'city',
        lon: 2.3522,
        lat: 48.8566,
        bbox: [2.224, 48.815, 2.469, 48.902],
      };

      const bounds = deriveGenerationBounds(place);
      expect(bounds.west).toBe(2.224);
      expect(bounds.south).toBe(48.815);
      expect(bounds.east).toBe(2.469);
      expect(bounds.north).toBe(48.902);
      expect(bounds.crossesAntimeridian).toBe(false);
    });

    it('synthesizes bounds when bbox is omitted', () => {
      const place: PlaceSuggestion = {
        id: 'poi-1',
        name: 'Eiffel Tower',
        subtitle: 'Paris, France',
        type: 'tourism',
        lon: 2.2945,
        lat: 48.8584,
      };

      const bounds = deriveGenerationBounds(place);
      expect(bounds.lonSpan).toBeGreaterThan(0);
      expect(bounds.latSpan).toBeGreaterThan(0);
      expect(bounds.south).toBeLessThan(place.lat);
      expect(bounds.north).toBeGreaterThan(place.lat);
    });

    it('falls back to local synthesis when bbox is degenerate (< min span)', () => {
      const place: PlaceSuggestion = {
        id: 'point-box',
        name: 'Bench',
        subtitle: 'Park',
        type: 'amenity',
        lon: 2.3,
        lat: 48.8,
        bbox: [2.30001, 48.80001, 2.30002, 48.80002],
      };

      const bounds = deriveGenerationBounds(place);
      // Degenerate bbox replaced with realistic local bounds
      expect(bounds.latSpan).toBeGreaterThan(0.05);
    });

    it('falls back to local synthesis when bbox is excessively large (country/continent)', () => {
      const countryPlace: PlaceSuggestion = {
        id: 'canada',
        name: 'Canada',
        subtitle: 'North America',
        type: 'country',
        lon: -106.3468,
        lat: 56.1304,
        bbox: [-141.0, 41.67, -52.64, 83.11], // Spans entire country
      };

      const bounds = deriveGenerationBounds(countryPlace);
      // Must not scatter drivers across entire continent
      expect(bounds.latSpan).toBeLessThanOrEqual(MAX_LAT_SPAN_DEG + 0.1);
      expect(bounds.lonSpan).toBeLessThanOrEqual(MAX_LON_SPAN_DEG + 0.3);
    });

    it('falls back to local synthesis when bbox contains invalid numbers', () => {
      const place: PlaceSuggestion = {
        id: 'invalid-bbox',
        name: 'Test',
        subtitle: 'Test',
        type: 'city',
        lon: 10,
        lat: 20,
        bbox: [NaN, 10, 20, 30] as unknown as [number, number, number, number],
      };

      const bounds = deriveGenerationBounds(place);
      expect(bounds.latSpan).toBeGreaterThan(0);
      expect(Number.isFinite(bounds.west)).toBe(true);
    });
  });

  describe('generateDrivers', () => {
    const londonPlace: PlaceSuggestion = {
      id: 'london-gb',
      name: 'London',
      subtitle: 'Greater London, England, United Kingdom',
      type: 'city',
      lon: -0.1276,
      lat: 51.5074,
      bbox: [-0.3514, 51.3849, 0.1482, 51.6723],
    };

    const tokyoPlace: PlaceSuggestion = {
      id: 'tokyo-jp',
      name: 'Tokyo',
      subtitle: 'Japan',
      type: 'city',
      lon: 139.6917,
      lat: 35.6895,
      bbox: [139.56, 35.53, 139.92, 35.81],
    };

    it('generates between 12 and 18 drivers', () => {
      const drivers = generateDrivers(londonPlace);
      expect(drivers.length).toBeGreaterThanOrEqual(12);
      expect(drivers.length).toBeLessThanOrEqual(18);
    });

    it('is completely deterministic: identical place returns identical drivers', () => {
      const run1 = generateDrivers(londonPlace);
      const run2 = generateDrivers(londonPlace);

      expect(run1).toEqual(run2);
    });

    it('generates different drivers for different places', () => {
      const londonDrivers = generateDrivers(londonPlace);
      const tokyoDrivers = generateDrivers(tokyoPlace);

      expect(londonDrivers[0].id).not.toBe(tokyoDrivers[0].id);
      expect(londonDrivers[0].lon).not.toBe(tokyoDrivers[0].lon);
      expect(londonDrivers[0].lat).not.toBe(tokyoDrivers[0].lat);
    });

    it('generates drivers with valid WGS-84 coordinates within bounds', () => {
      const drivers = generateDrivers(londonPlace);
      const bounds = deriveGenerationBounds(londonPlace);

      for (const d of drivers) {
        expect(isValidCoordinate(d.lon, d.lat)).toBe(true);
        expect(d.lat).toBeGreaterThanOrEqual(bounds.south);
        expect(d.lat).toBeLessThanOrEqual(bounds.north);
        expect(d.lon).toBeGreaterThanOrEqual(bounds.west);
        expect(d.lon).toBeLessThanOrEqual(bounds.east);
      }
    });

    it('assigns valid statuses ("available" or "busy")', () => {
      const drivers = generateDrivers(londonPlace);
      const statuses = new Set(drivers.map((d) => d.status));

      for (const status of statuses) {
        expect(['available', 'busy']).toContain(status);
      }
      expect(statuses.has('available')).toBe(true);
    });

    it('handles high-latitude places safely (e.g. Tromsø 69.6° N)', () => {
      const tromsoPlace: PlaceSuggestion = {
        id: 'tromso-no',
        name: 'Tromsø',
        subtitle: 'Troms, Norway',
        type: 'city',
        lon: 18.9553,
        lat: 69.6492,
      };

      const drivers = generateDrivers(tromsoPlace);
      expect(drivers.length).toBeGreaterThanOrEqual(12);
      for (const d of drivers) {
        expect(isValidCoordinate(d.lon, d.lat)).toBe(true);
        expect(d.lat).toBeGreaterThan(69.0);
        expect(d.lat).toBeLessThan(70.5);
      }
    });

    it('handles antimeridian places safely (e.g. Taveuni, Fiji near 180°)', () => {
      const fijiPlace: PlaceSuggestion = {
        id: 'fiji-island',
        name: 'Taveuni',
        subtitle: 'Northern Division, Fiji',
        type: 'island',
        lon: 179.98,
        lat: -16.82,
      };

      const drivers = generateDrivers(fijiPlace);
      expect(drivers.length).toBeGreaterThanOrEqual(12);
      for (const d of drivers) {
        expect(isValidCoordinate(d.lon, d.lat)).toBe(true);
        expect(d.lon).toBeGreaterThanOrEqual(-180);
        expect(d.lon).toBeLessThanOrEqual(180);
      }
    });

    it('returns empty array when place is null or undefined', () => {
      expect(generateDrivers(null)).toEqual([]);
      expect(generateDrivers(undefined)).toEqual([]);
    });

    it('returns empty array when place has invalid coordinates', () => {
      const invalidPlace: PlaceSuggestion = {
        id: 'invalid',
        name: 'Nowhere',
        subtitle: 'Void',
        type: 'nowhere',
        lon: 999,
        lat: NaN,
      };
      expect(generateDrivers(invalidPlace)).toEqual([]);
    });
  });
});
