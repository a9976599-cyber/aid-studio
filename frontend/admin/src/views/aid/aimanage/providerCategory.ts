import type { Provider } from './types';

export type ProviderCategory = 'AGGREGATOR' | 'OFFICIAL';
export const PROVIDER_CATEGORIES: { value: ProviderCategory; label: string; description: string }[] = [
  { value: 'AGGREGATOR', label: '三方聚合', description: '通过聚合服务商接入模型' },
  { value: 'OFFICIAL', label: '官方厂商', description: '直接对接模型原厂' }
];

export function providerCategory(provider?: Partial<Provider> | null): ProviderCategory {
  return provider?.providerCategory === 'OFFICIAL' ? 'OFFICIAL' : 'AGGREGATOR';
}

export function providerCategoryLabel(provider?: Partial<Provider> | null): string {
  return providerCategory(provider) === 'OFFICIAL' ? '官方厂商' : '三方聚合';
}

export function compareProviders(a: Provider, b: Provider): number {
  const group = Number(providerCategory(a) === 'OFFICIAL') - Number(providerCategory(b) === 'OFFICIAL');
  return group || (a.displayOrder ?? 100) - (b.displayOrder ?? 100) || a.id - b.id;
}
