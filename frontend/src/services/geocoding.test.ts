/**
 * Unit tests for the Photon geocoding service (geocoding.ts).
 * Uses vitest globals. Mocks global `fetch`.
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { PhotonGeocodingService } from './geocoding';

const service = new PhotonGeocodingService();

function makeFeature(overrides: Record<string, unknown> = {}) {
  return {
    type: 'Feature',
    properties: {
      name: 'Paris',
      city: 'Paris',
      state: 'Île-de-France',
      country: 'France',
      countrycode: 'FR',
      type: 'city',
      osm_type: 'R',
      osm_id: 71525,
      extent: [2.224122, 48.902156, 2.4697602, 48.8155755],
      ...overrides,
    },
    geometry: {
      type: 'Point',
      coordinates: [2.3483915, 48.8534951],
    },
  };
}

function makeResponse(features: unknown[], status = 200) {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: vi.fn().mockResolvedValue({ type: 'FeatureCollection', features }),
  };
}

beforeEach(() => {
  vi.restoreAllMocks();
});

describe('PhotonGeocodingService.search', () => {
  it('returns empty for queries shorter than 2 characters', async () => {
    const result = await service.search('a', new AbortController().signal);
    expect(result.status).toBe('empty');
  });

  it('returns ok with normalised suggestions for a valid response', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(makeResponse([makeFeature()])),
    );
    const signal = new AbortController().signal;
    const result = await service.search('Paris', signal);
    expect(result.status).toBe('ok');
    if (result.status !== 'ok') return;
    const [s] = result.suggestions;
    expect(s.name).toBe('Paris');
    expect(s.subtitle).toContain('France');
    expect(s.lon).toBeCloseTo(2.3483915, 5);
    expect(s.lat).toBeCloseTo(48.8534951, 5);
  });

  it('parses a valid bbox and remaps Photon format to MapLibre format', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(makeResponse([makeFeature()])),
    );
    const result = await service.search('Paris', new AbortController().signal);
    expect(result.status).toBe('ok');
    if (result.status !== 'ok') return;
    const bbox = result.suggestions[0].bbox!;
    // [west, south, east, north]
    expect(bbox[0]).toBeCloseTo(2.224122, 4);  // west = minLon
    expect(bbox[1]).toBeCloseTo(48.8155755, 4); // south = minLat
    expect(bbox[2]).toBeCloseTo(2.4697602, 4);  // east = maxLon
    expect(bbox[3]).toBeCloseTo(48.902156, 4);  // north = maxLat
  });

  it('drops bbox when extent values are invalid', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        makeResponse([makeFeature({ extent: [NaN, 48.9, 2.47, 48.8] })]),
      ),
    );
    const result = await service.search('Paris', new AbortController().signal);
    expect(result.status).toBe('ok');
    if (result.status !== 'ok') return;
    expect(result.suggestions[0].bbox).toBeUndefined();
  });

  it('drops bbox when extent is a degenerate (near-zero-area) box', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        makeResponse([makeFeature({ extent: [2.348, 48.853, 2.3481, 48.8531] })]),
      ),
    );
    const result = await service.search('Paris', new AbortController().signal);
    if (result.status !== 'ok') return;
    expect(result.suggestions[0].bbox).toBeUndefined();
  });

  it('skips features with invalid coordinates', async () => {
    const bad = {
      ...makeFeature(),
      geometry: { type: 'Point', coordinates: [999, 200] }, // out of range
    };
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(makeResponse([bad])),
    );
    const result = await service.search('Paris', new AbortController().signal);
    expect(result.status).toBe('empty');
  });

  it('returns empty when features array is empty', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(makeResponse([])),
    );
    const result = await service.search('xyz', new AbortController().signal);
    expect(result.status).toBe('empty');
  });

  it('returns retryable error on HTTP 429', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({ ok: false, status: 429, json: vi.fn() }),
    );
    const result = await service.search('Tokyo', new AbortController().signal);
    expect(result.status).toBe('error');
    if (result.status !== 'error') return;
    expect(result.retryable).toBe(true);
  });

  it('returns retryable error on network failure', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockRejectedValue(new TypeError('Failed to fetch')),
    );
    const result = await service.search('Tokyo', new AbortController().signal);
    expect(result.status).toBe('error');
    if (result.status !== 'error') return;
    expect(result.retryable).toBe(true);
  });

  it('returns empty (not error) when request is aborted', async () => {
    const ctrl = new AbortController();
    const abortError = new DOMException('Aborted', 'AbortError');
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(abortError));
    ctrl.abort();
    const result = await service.search('London', ctrl.signal);
    expect(result.status).toBe('empty');
  });

  it('returns non-retryable error on malformed JSON', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: vi.fn().mockRejectedValue(new SyntaxError('Unexpected token')),
    }));
    const result = await service.search('Berlin', new AbortController().signal);
    expect(result.status).toBe('error');
    if (result.status !== 'error') return;
    expect(result.retryable).toBe(false);
  });
});
