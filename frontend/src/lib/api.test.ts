import { afterEach, describe, expect, it } from 'vitest'
import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios'
import { api, apiUploadAvatar } from './api'

/**
 * The instance used to declare `Content-Type: application/json` as a default.
 * axios runs the transformRequest before the adapter, and that transform keys
 * off the declared content type: given a FormData body and a JSON content type
 * it serialised the form to JSON and dropped the file, so the upload reached
 * the server as `{"file":{}}` instead of a multipart part.
 *
 * The adapter runs after the transform, so what it receives is exactly what the
 * old default would have mangled.
 */
describe('apiUploadAvatar', () => {
  const originalAdapter = api.defaults.adapter

  afterEach(() => {
    api.defaults.adapter = originalAdapter
  })

  it('sends the picked file as multipart rather than JSON', async () => {
    const seen: { data: unknown; contentType: string | null }[] = []
    api.defaults.adapter = async (config: InternalAxiosRequestConfig): Promise<AxiosResponse> => {
      seen.push({
        data: config.data,
        contentType: config.headers.get('Content-Type')?.toString() ?? null,
      })
      return { data: null, status: 201, statusText: 'Created', headers: {}, config }
    }

    const file = new File(['png-bytes'], 'avatar.png', { type: 'image/png' })
    await apiUploadAvatar(file)

    expect(seen).toHaveLength(1)
    // A string body here is the defect: it means the FormData was serialised.
    expect(seen[0].data).toBeInstanceOf(FormData)
    expect((seen[0].data as FormData).get('file')).toBe(file)
    expect(String(seen[0].contentType ?? '')).not.toContain('application/json')
  })
})
