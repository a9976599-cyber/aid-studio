import React, { useEffect, useRef, useState } from 'react';
import { Alert, Button, Descriptions, Modal, Select, Space, Table, Tabs, Tag, message } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { bindNewApiToken, createNewApiToken, importNewApiModels, getNewApiAccount, getNewApiCatalog, getNewApiTokens,
  type NewApiAccount, type NewApiToken, type NewApiCatalogModel } from '@/api/aid/newapi';
import type { Provider } from './types';

interface Props { open: boolean; provider: Provider | null; onClose: () => void; onChanged: () => void }

export default function NewApiAccountModal({ open, provider, onClose, onChanged }: Props) {
  const [account, setAccount] = useState<NewApiAccount | null>(null);
  const [group, setGroup] = useState('');
  const [tokens, setTokens] = useState<NewApiToken[]>([]);
  const [tokenPage, setTokenPage] = useState(1);
  const [tokenTotal, setTokenTotal] = useState(0);
  const [models, setModels] = useState<NewApiCatalogModel[]>([]);
  const [previews, setPreviews] = useState<Record<string, {
    fingerprint: string; blockers: string[]; pricingSource?: string; pricingLabel?: string
  }>>({});
  const [selected, setSelected] = useState<React.Key[]>([]);
  const [importing, setImporting] = useState(false);
  const importFlight = useRef(false);
  const [loading, setLoading] = useState(false);
  const [binding, setBinding] = useState<number | null>(null);
  const bindingFlight = useRef(false);
  const scope = useRef(0);
  const [keyword, setKeyword] = useState('');
  const enabled = open && !!provider?.newApiSystemTokenEnabled && provider.integrationType === 'NEW_API';

  useEffect(() => {
    const current = ++scope.current;
    setAccount(null); setTokens([]); setModels([]); setPreviews({}); setSelected([]); setGroup(''); setTokenPage(1); setKeyword('');
    if (!enabled || !provider) return;
    setLoading(true);
    Promise.all([getNewApiAccount(provider.id), getNewApiTokens(provider.id)])
      .then(([a, t]) => {
        if (scope.current !== current) return;
        setAccount(a.data); setTokens(t.data.items); setTokenTotal(t.data.total);
        setGroup(a.data.groups.some((g: any) => g.name === provider.newApiGroup)
          ? provider.newApiGroup : a.data.groups[0]?.name || '');
      }).finally(() => { if (scope.current === current) setLoading(false); }).catch(() => {});
    return () => { scope.current = current + 1; };
  }, [enabled, provider]);

  const refreshCatalog = async () => {
    if (!provider || !group || loading) return;
    const current = scope.current;
    setLoading(true);
    try {
      const result = await getNewApiCatalog(provider.id, group, true);
      if (scope.current === current) {
        setModels(result.data.models); setSelected([]);
        setPreviews(Object.fromEntries(result.data.previews.map((item: any) => [item.modelId, item])));
      }
    } finally { if (scope.current === current) setLoading(false); }
  };

  const bind = async (token: NewApiToken) => {
    if (!provider || bindingFlight.current) return;
    bindingFlight.current = true; setBinding(token.id);
    try {
      await bindNewApiToken(provider.id, token.id, group);
      message.success('已绑定此分组的调用 Key'); setModels([]); setPreviews({}); setSelected([]); onChanged();
    } finally { bindingFlight.current = false; setBinding(null); }
  };

  const changePage = async (page: number) => {
    if (!provider || loading) return;
    const current = scope.current;
    setLoading(true);
    try {
      const result = await getNewApiTokens(provider.id, page);
      if (scope.current === current) { setTokens(result.data.items); setTokenTotal(result.data.total); setTokenPage(page); }
    } finally { if (scope.current === current) setLoading(false); }
  };

  const createKey = async () => {
    if (!provider || !group || bindingFlight.current) return;
    bindingFlight.current = true; setBinding(-1);
    try {
      await createNewApiToken(provider.id, group);
      message.success('专用 Key 已创建并绑定'); setModels([]); setPreviews({}); setSelected([]); onChanged();
    } finally { bindingFlight.current = false; setBinding(null); }
  };

  const importModels = async () => {
    if (!provider || !selected.length || importFlight.current) return;
    importFlight.current = true; setImporting(true);
    const completed: React.Key[] = [];
    let created = 0;
    let existing = 0;
    try {
      for (const id of selected) {
        const result = await importNewApiModels(provider.id, group, [{ modelId: String(id), fingerprint: previews[String(id)].fingerprint }]);
        created += result.data.created.length;
        existing += result.data.existing.length;
        completed.push(id);
      }
      message.success(`已添加 ${created} 个模型，保留 ${existing} 个已有模型`);
      setSelected([]);
    } catch {
      setSelected(selected.filter(id => !completed.includes(id)));
      if (completed.length) message.warning(`已处理 ${completed.length} 个模型，其余可重试`);
    } finally {
      if (completed.length) onChanged();
      importFlight.current = false; setImporting(false);
    }
  };

  return <Modal open={open} title={`${provider?.providerName || 'New API'} · 账户与模型`} width={1000} footer={null} onCancel={onClose} destroyOnClose>
    {!enabled ? <Alert type="info" showIcon message="系统访问令牌已关闭" description="可使用地址和模型 API Key 手动添加模型；如需读取账户分组与目录，请在供应商配置中开启账户授权。" /> : <>
      {account && <Descriptions size="small" column={3} items={[
        { key: 'user', label: '上游账户', children: account.username },
        { key: 'id', label: '用户 ID', children: account.userId },
        { key: 'bound', label: '已绑定分组', children: provider?.newApiGroup || '尚未绑定' }
      ]} />}
      <Space style={{ margin: '16px 0' }} wrap>
        <span>分组</span>
        <Select aria-label="上游分组" value={group || undefined} style={{ minWidth: 260 }} loading={loading}
          disabled={loading || binding !== null} options={account?.groups.map(g => ({ value: g.name, label: `${g.name}${g.description ? ` · ${g.description}` : ''}` }))}
          onChange={(value) => { setGroup(value); setModels([]); setPreviews({}); setSelected([]); }} />
        <Button icon={<ReloadOutlined />} loading={loading} disabled={!group} onClick={() => { void refreshCatalog().catch(() => {}); }}>获取当前分组模型</Button>
      </Space>
      <Tabs items={[
        { key: 'tokens', label: '调用 Key', children: <>
          <Alert type="info" showIcon message="选择本组的可用 Key" description="绑定后只在服务端保存完整 Key；目录会同时检查分组权限与该 Key 的模型限制。" style={{ marginBottom: 12 }} />
          <Button disabled={!group || binding !== null || loading} loading={binding === -1} style={{ marginBottom: 12 }} onClick={() => { void createKey().catch(() => {}); }}>创建并绑定本组专用 Key</Button>
          <Table<NewApiToken> rowKey="id" size="small" loading={loading} dataSource={tokens.filter(t => t.group === group)}
            pagination={{ current: tokenPage, total: tokenTotal, pageSize: 100, showSizeChanger: false, onChange: page => { void changePage(page).catch(() => {}); } }} columns={[
              { title: '名称', dataIndex: 'name' }, { title: '分组', dataIndex: 'group' },
              { title: '模型权限', render: (_, t) => t.modelLimitsEnabled ? '指定模型' : '分组全部模型' },
              { title: '状态', render: (_, t) => <Tag>{t.status === 1 ? '启用' : '不可用'}</Tag> },
              { title: '操作', render: (_, t) => <Button size="small" loading={binding === t.id}
                disabled={binding !== null || t.status !== 1 || (t.expiredTime > 0 && t.expiredTime <= Date.now() / 1000) || (!t.unlimitedQuota && t.remainQuota <= 0)}
                onClick={() => { void bind(t).catch(() => {}); }}>{provider?.newApiTokenId === t.id ? '重新绑定' : '绑定此 Key'}</Button> }
            ]} />
        </> },
        { key: 'models', label: `分组模型${models.length ? ` (${models.length})` : ''}`, children: <>
          <Space style={{ marginBottom: 12 }}><Button type="primary" loading={importing} disabled={!selected.length || selected.length > 20 || group !== provider?.newApiGroup || !provider?.newApiTokenId} onClick={() => { void importModels().catch(() => {}); }}>添加所选模型{selected.length ? ` (${selected.length})` : ''}</Button><span>每次最多 20 个；新增模型会按类型逐个实际生成并消耗上游额度，成功后才保存。</span></Space>
          <Select showSearch value={keyword || undefined} allowClear placeholder="搜索模型" style={{ width: '100%', marginBottom: 12 }}
            options={models.map(m => ({ value: m.id, label: m.id }))} onChange={value => setKeyword(value || '')} />
          <Table<NewApiCatalogModel> size="small" rowKey="id" loading={loading} dataSource={models.filter(m => !keyword || m.id === keyword)}
            rowSelection={{ selectedRowKeys: selected, onChange: setSelected, getCheckboxProps: m => ({ disabled: !previews[m.id]?.fingerprint || !!previews[m.id]?.blockers.length || importing }) }}
            pagination={{ pageSize: 15 }} columns={[{ title: '上游模型', dataIndex: 'id' },
              { title: '可用接口', render: (_, m) => m.endpoints.map(e => <Tag key={e}>{e}</Tag>) },
              { title: '计价方式', render: (_, m) => previews[m.id]?.pricingLabel || (m.pricing?.billing_mode || (m.pricing ? m.pricing.quota_type === 0 ? 'Token' : '按次' : '未返回价格')) },
              { title: '添加检查', render: (_, m) => previews[m.id]?.blockers.length ? previews[m.id].blockers.join('；') : <Tag color="success">配置完整</Tag> }
            ]} />
        </> }
      ]} />
    </>}
  </Modal>;
}
