import React, { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { Button, Input, Switch, Tooltip, message } from 'antd';
import { ApiOutlined, PlusOutlined, EditOutlined, DeleteOutlined, SearchOutlined, ExperimentOutlined, DollarCircleOutlined, UnorderedListOutlined, SafetyCertificateOutlined, DatabaseOutlined } from '@ant-design/icons';
import type { Provider, ProviderOperationCapabilities } from './types';
import { getProviderOperationCapabilities } from '@/api/aid/aimanage';
import ProviderOperationsModal from './ProviderOperationsModal';
import NewApiAccountModal from './NewApiAccountModal';
import { isTokenDanceProvider } from './recommendedProvider';
import { PROVIDER_CATEGORIES, providerCategory, type ProviderCategory } from './providerCategory';
import { resolveProviderLogo } from '@/utils/builtinImages';
import {
  createProviderCapabilityScope,
  ownsProviderCapabilities,
  providerCapabilityScopeKey,
  type ProviderCapabilityScope
} from './providerOperations';
import { runConfigTest, type ConfigTestResult } from '@/api/system/configTest';
import TestResultModal from '@/components/TestResultModal';

interface Props {
  list: Provider[];
  loading: boolean;
  active: Provider | null;
  modelCounts: Record<number, number>;
  onSelect: (p: Provider) => void;
  onAdd: () => void;
  onEdit: () => void;
  onDelete: () => void;
  /** 行内开关：直接启用/停用服务商，无需进入编辑弹窗 */
  onToggleStatus: (p: Provider, enabled: boolean) => Promise<void>;
  /** 聚合供应商区与侧栏共用一个弹窗入口。 */
  onTokenDanceAction: (action: 'account' | 'catalog') => void;
  onProviderChanged: () => void;
}

export default function ProviderPanel(props: Props) {
  const { list, loading, active, modelCounts, onSelect, onAdd, onEdit, onDelete, onToggleStatus, onTokenDanceAction } = props;
  const [kw, setKw] = useState('');
  const [expandedCategory, setExpandedCategory] = useState<ProviderCategory | null>('AGGREGATOR');
  const listRef = useRef<HTMLDivElement>(null);
  const [testing, setTesting] = useState(false);
  const [testOpen, setTestOpen] = useState(false);
  const [testResult, setTestResult] = useState<ConfigTestResult | null>(null);
  // 正在切换启停的服务商 id（用于对应行 Switch 的 loading 态）
  const [togglingId, setTogglingId] = useState<number | null>(null);
  const [operations, setOperations] = useState<ProviderOperationCapabilities>({});
  const [operationsScope, setOperationsScope] = useState<ProviderCapabilityScope | null>(null);
  const [operationsOpen, setOperationsOpen] = useState(false);
  const [newApiOpen, setNewApiOpen] = useState(false);
  const [operationsTab, setOperationsTab] = useState<'balance' | 'tasks'>('balance');
  const activeCapabilityScope = createProviderCapabilityScope(active);
  const activeCapabilityScopeKey = providerCapabilityScopeKey(activeCapabilityScope);
  const isTokenDance = isTokenDanceProvider(active);

  useEffect(() => {
    let alive = true;
    setOperations({});
    setOperationsScope(null);
    setOperationsOpen(false);
    if (!active || !activeCapabilityScope) return;
    const requestedScope = activeCapabilityScope;
    getProviderOperationCapabilities(requestedScope.providerId)
      .then((res: any) => {
        if (alive) {
          setOperations(res.data || {});
          setOperationsScope(requestedScope);
        }
      })
      .catch(() => {
        if (alive) {
          setOperations({});
          setOperationsScope(requestedScope);
        }
      });
    return () => { alive = false; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeCapabilityScopeKey]);

  // id 或规范化 providerCode 改变后的首帧即隐藏旧能力，不等待 effect 发出新请求。
  const activeOperations = ownsProviderCapabilities(operationsScope, active) ? operations : {};

  const openOperations = (tab: 'balance' | 'tasks') => {
    setOperationsTab(tab);
    setOperationsOpen(true);
  };

  const handleTest = async () => {
    if (!active || testing) return;
    setTesting(true);
    try {
      const res = await runConfigTest('ai-provider', { providerId: active.id });
      setTestResult(res.data);
      setTestOpen(true);
    } catch (e: any) {
      message.error(e?.message || '测试请求失败');
    } finally {
      setTesting(false);
    }
  };

  const handleToggle = async (p: Provider, enabled: boolean) => {
    if (togglingId != null) return;
    setTogglingId(p.id);
    try {
      await onToggleStatus(p, enabled);
    } catch {
      // 失败提示由请求拦截器统一弹出，这里吞掉避免未捕获异常；开关状态保持原值
    } finally {
      setTogglingId(null);
    }
  };

  const filtered = useMemo(() => {
    if (!kw) return list;
    const lower = kw.toLowerCase();
    return list.filter(
      (p) =>
        (p.providerName || '').toLowerCase().includes(lower) ||
        (p.providerCode || '').toLowerCase().includes(lower)
    );
  }, [list, kw]);

  // 展开选中行的操作区后，保证整行（包括操作按钮）仍在当前分组的滚动区域内。
  useLayoutEffect(() => {
    if (!active || providerCategory(active) !== expandedCategory) return;
    const row = listRef.current?.querySelector<HTMLElement>(`[data-provider-id="${active.id}"]`);
    const body = row?.closest<HTMLElement>('.provider-group__body');
    if (!row || !body) return;
    const rowBounds = row.getBoundingClientRect();
    const bodyBounds = body.getBoundingClientRect();
    if (rowBounds.bottom > bodyBounds.bottom) body.scrollTop += rowBounds.bottom - bodyBounds.bottom + 8;
    else if (rowBounds.top < bodyBounds.top) body.scrollTop -= bodyBounds.top - rowBounds.top + 8;
  }, [active?.id, expandedCategory, filtered]);

  const handleSearch = (value: string) => {
    setKw(value);
    const term = value.trim().toLowerCase();
    if (!term) return;
    const matches = list.filter((p) =>
      (p.providerName || '').toLowerCase().includes(term) ||
      (p.providerCode || '').toLowerCase().includes(term)
    );
    if (matches.length && !matches.some((p) => providerCategory(p) === expandedCategory)) {
      setExpandedCategory(providerCategory(matches[0]));
      onSelect(matches[0]);
    }
  };

  return (
    <div className="aimanage-sidebar" aria-busy={loading}>
      <div className="aimanage-sidebar__header">
        <ApiOutlined /> 已配置服务商
        <span className="provider-total">{list.length}</span>
      </div>
      <div className="aimanage-sidebar__search">
        <Input size="small" prefix={<SearchOutlined />} placeholder="搜索服务商..." value={kw} onChange={(e) => handleSearch(e.target.value)} allowClear />
      </div>
      <div className="aimanage-sidebar__list" ref={listRef}>
        {PROVIDER_CATEGORIES.map((category) => {
          const providers = filtered.filter((p) => providerCategory(p) === category.value);
          const expanded = expandedCategory === category.value;
          return <section key={category.value} className={`provider-group ${expanded ? 'provider-group--expanded' : ''}`} aria-label={category.label}>
            <button type="button" className="provider-group__heading" aria-expanded={expanded}
              onClick={() => {
                setExpandedCategory(expanded ? null : category.value);
                if (!expanded && providers.length && (!active || providerCategory(active) !== category.value)) {
                  onSelect(providers.find((provider) => provider.status === '0' && modelCounts[provider.id] > 0)
                    || providers.find((provider) => provider.status === '0') || providers[0]);
                }
              }}>
              <span>{category.label}</span><span className="provider-group__count">{providers.length}</span>
              <span aria-hidden className="provider-group__chevron">{expanded ? '−' : '+'}</span>
            </button>
            {expanded && <div className="provider-group__body">{providers.map((p) => {
          const isActive = active?.id === p.id;
          const enabled = p.status === '0';
          return (
            <div
              key={p.id}
              data-provider-id={p.id}
              className={`aimanage-sidebar__item ${isActive ? 'active' : ''} ${enabled ? '' : 'stopped'}`}
            >
              <div className="provider-row">
                {/* 服务商 LOGO：有则展示图标，无则用首字母占位，保持列表对齐 */}
                {resolveProviderLogo(p.providerCode, p.logoUrl) ? (
                  <img
                    src={resolveProviderLogo(p.providerCode, p.logoUrl)}
                    alt={p.providerName}
                    className="provider-logo"
                    onError={(e) => { (e.currentTarget as HTMLImageElement).style.display = 'none'; }}
                  />
                ) : (
                  <span className="provider-logo provider-logo--placeholder">
                    {(p.providerName || p.providerCode || '?').slice(0, 1).toUpperCase()}
                  </span>
                )}
                <button type="button" className="provider-name provider-select" title={p.providerName}
                  aria-pressed={isActive} onClick={() => onSelect(p)}>{p.providerName}</button>
                {/* 启停开关：阻止冒泡，避免切换时误选中该行 */}
                <span onClick={(e) => e.stopPropagation()} style={{ flexShrink: 0 }}>
                  <Tooltip title={enabled ? '点击停用' : '点击启用'}>
                    <Switch
                      size="small"
                      checked={enabled}
                      loading={togglingId === p.id}
                      onChange={(checked) => handleToggle(p, checked)}
                    />
                  </Tooltip>
                </span>
              </div>
              <div className="provider-sub">
                <span className="provider-code">{p.providerCode}</span>
                <span className="model-count">{modelCounts[p.id] || 0} 个模型</span>
              </div>
              {/* 选中的服务商直接内联操作，不用再去底部找按钮 */}
              {isActive && (
                <div className="provider-actions" onClick={(e) => e.stopPropagation()}>
                  <Button size="small" icon={<EditOutlined />} onClick={onEdit}>编辑</Button>
                  <Button size="small" icon={<ExperimentOutlined />} loading={testing} onClick={handleTest}>测试</Button>
                  {isTokenDance && <Button size="small" icon={<SafetyCertificateOutlined />} onClick={() => onTokenDanceAction('account')}>账户</Button>}
                  {isTokenDance && <Button size="small" icon={<DatabaseOutlined />} onClick={() => onTokenDanceAction('catalog')}>目录</Button>}
                  {p.integrationType === 'NEW_API' && p.newApiSystemTokenEnabled && <Button size="small" icon={<DatabaseOutlined />} onClick={() => setNewApiOpen(true)}>账户与模型</Button>}
                  {!isTokenDance && activeOperations.balance && <Button size="small" icon={<DollarCircleOutlined />} onClick={() => openOperations('balance')}>余额</Button>}
                  {activeOperations.upstreamTasks && <Button size="small" icon={<UnorderedListOutlined />} onClick={() => openOperations('tasks')}>任务</Button>}
                  {!isTokenDance && <Button danger size="small" icon={<DeleteOutlined />} onClick={onDelete}>删除</Button>}
                </div>
              )}
            </div>
          );
        })}
            {providers.length === 0 && <div className="provider-group__empty">{kw.trim() ? '没有匹配的供应商' : '暂无已配置供应商'}</div>}</div>}
          </section>;
        })}
      </div>
      <div className="aimanage-sidebar__footer">
        <Button type="primary" icon={<PlusOutlined />} block onClick={onAdd}>新增服务商</Button>
      </div>
      <TestResultModal open={testOpen} result={testResult} onClose={() => setTestOpen(false)} />
      <NewApiAccountModal open={newApiOpen} provider={active} onClose={() => setNewApiOpen(false)} onChanged={props.onProviderChanged} />
      <ProviderOperationsModal open={operationsOpen} provider={active} capabilities={activeOperations} initialTab={operationsTab} onClose={() => setOperationsOpen(false)} />
    </div>
  );
}
