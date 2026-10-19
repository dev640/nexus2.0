import { afterEach, describe, expect, it } from 'vitest'
import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios'
import {
  api,
  apiCreateCompany,
  apiListAdminAccounts,
  apiListAdminCompanies,
  apiResumeCompany,
  apiSuspendCompany,
  apiUploadAvatar,
} from './api'

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

describe('SUPER_ADMIN company API', () => {
  const originalAdapter = api.defaults.adapter

  afterEach(() => {
    api.defaults.adapter = originalAdapter
  })

  it('uses the operator routes and sends the optional company filter', async () => {
    const seen: { method: string; url: string; params: unknown; data: unknown }[] = []
    api.defaults.adapter = async (config: InternalAxiosRequestConfig): Promise<AxiosResponse> => {
      seen.push({
        method: config.method ?? '',
        url: config.url ?? '',
        params: config.params,
        data: config.data,
      })
      return { data: [], status: 200, statusText: 'OK', headers: {}, config }
    }

    await apiListAdminCompanies()
    await apiListAdminAccounts(12)
    await apiCreateCompany({ name: 'North', adminName: 'Ada', adminEmail: 'ada@example.test' })
    await apiSuspendCompany(12)
    await apiResumeCompany(12)

    expect(seen.map(({ method, url }) => [method, url])).toEqual([
      ['get', '/admin/companies'],
      ['get', '/admin/accounts'],
      ['post', '/admin/companies'],
      ['post', '/admin/companies/12/suspend'],
      ['post', '/admin/companies/12/resume'],
    ])
    expect(seen[1].params).toEqual({ companyId: 12 })
    expect(JSON.parse(String(seen[2].data))).toEqual({
      name: 'North',
      adminName: 'Ada',
      adminEmail: 'ada@example.test',
    })
  })
})
