import { request } from '@/utils/request';
import { getToken } from '@/utils/auth';

const flights = new Map<string, Promise<any>>();
const results = new Map<string, { at: number; result: any }>();
function query(providerId: number, resource: string, params: Record<string, unknown> = {}, force = false) {
  const key = JSON.stringify([getToken(), providerId, resource, Object.entries(params).sort()]);
  const running = flights.get(key);
  if (running) return running;
  const cached = results.get(key);
  if (!force && cached && Date.now() - cached.at < 1500) return Promise.resolve(cached.result);
  const run = request({ url: `/aid/newapi/${providerId}/${resource}`, method: 'get', params })
    .then((result) => {
      for (const [oldKey, value] of results) if (Date.now() - value.at >= 1500) results.delete(oldKey);
      if (results.size >= 100) results.clear();
      results.set(key, { at: Date.now(), result });
      return result;
    }).finally(() => flights.delete(key));
  flights.set(key, run);
  return run;
}

export const getNewApiAccount = (id: number, force = false) => query(id, 'account', {}, force);
export const getNewApiTokens = (id: number, page = 1, force = false) => query(id, 'tokens', { page }, force);
export const getNewApiCatalog = (id: number, group: string, force = false) => query(id, 'catalog', { group }, force);
export function bindNewApiToken(id: number, tokenId: number, group: string) {
  return request({ url: `/aid/newapi/${id}/bind-token`, method: 'post', data: { tokenId, group } });
}
export function createNewApiToken(id: number, group: string) {
  return request({ url: `/aid/newapi/${id}/create-token`, method: 'post', data: { group } });
}
export function importNewApiModels(id: number, group: string, selections: { modelId: string; fingerprint: string }[]) {
  return request({ url: `/aid/newapi/${id}/import`, method: 'post', data: { group, selections }, timeout: 360000 });
}

export interface NewApiGroup { name: string; description: string; ratio: number | null }
export interface NewApiAccount { userId: number; username: string; group: string; groups: NewApiGroup[] }
export interface NewApiToken {
  id: number; name: string; group: string; status: number; expiredTime: number;
  unlimitedQuota: boolean; remainQuota: number; modelLimitsEnabled: boolean;
}
export interface NewApiCatalogModel {
  id: string; owner: string; endpoints: string[]; pricing: Record<string, any> | null;
}
