import React, { useEffect, useState } from 'react';
import { Alert, Button, Col, Form, Input, InputNumber, Modal, Row, Select, Switch, Tabs } from 'antd';
import { LinkOutlined } from '@ant-design/icons';
import { ENABLE_STATUS_OPTIONS, DISPATCH_MODE_OPTIONS } from '@/utils/enums';
import { makeDefaultScheduleStrategy } from './constants';
import { PROVIDER_CATEGORIES, providerCategory } from './providerCategory';
import type { Provider, ScheduleStrategy } from './types';
import JsonObjectEditor, { KvPreset } from './JsonObjectEditor';
import ImageUpload from '@/components/ImageUpload';
import { resolveProviderLogo } from '@/utils/builtinImages';
import { normalizeRelativeEndpoint, validateRelativeEndpoint } from './endpointPath';
import {
  normalizeCallbackProviderCode,
  resolveProviderCallbackPath,
  validateProviderCallbackUrl
} from './providerCallback';

interface Props {
  open: boolean;
  title: string;
  data?: Partial<Provider>;
  onCancel: () => void;
  onOk: (values: any) => Promise<void>;
}

/** extra_headers 常用预设（不同厂商的鉴权辅助 header） */
const EXTRA_HEADERS_PRESETS: KvPreset[] = [
  { key: 'api-version', value: '2024-02-01', label: 'api-version', tooltip: 'Azure OpenAI 必填的 API 版本号' },
  { key: 'OpenAI-Beta', value: 'assistants=v2', label: 'OpenAI-Beta', tooltip: 'OpenAI 实验功能开关' },
  { key: 'X-Project-Id', value: '', label: 'X-Project-Id', tooltip: 'Anthropic 项目隔离' }
];

/** extra_body 常用预设（除思考模式外的常见模型参数） */
const EXTRA_BODY_PRESETS: KvPreset[] = [
  { key: 'temperature', value: 0.7, label: 'temperature', tooltip: '采样温度，0~2' },
  { key: 'top_p', value: 0.95, label: 'top_p', tooltip: '核采样阈值' },
  { key: 'frequency_penalty', value: 0, label: 'frequency_penalty', tooltip: '频率惩罚 -2~2' },
  { key: 'presence_penalty', value: 0, label: 'presence_penalty', tooltip: '存在惩罚 -2~2' },
  { key: 'seed', value: 42, label: 'seed', tooltip: '随机种子（OpenAI/DeepSeek 支持）' },
  { key: 'response_format', value: { type: 'json_object' }, label: 'response_format', tooltip: '强制返回 JSON' }
];

/** extra_query 常用预设 */
const EXTRA_QUERY_PRESETS: KvPreset[] = [
  { key: 'api-version', value: '2024-02-01', label: 'api-version', tooltip: 'Azure 部分接口需要写在 query 上' },
  { key: 'region', value: 'cn-beijing', label: 'region', tooltip: '阿里云区域代码' }
];

/** 服务商网关只允许协议、主机和可选端口，接口版本与路径由模型配置承载。 */
const validateBaseGatewayUrl = (_: unknown, value?: string) => {
  if (!value?.trim()) return Promise.resolve();
  try {
    const url = new URL(value.trim());
    const supportedProtocol = url.protocol === 'http:' || url.protocol === 'https:';
    const basePath = url.pathname === '/' || url.pathname === '';
    if (!supportedProtocol || !url.hostname || url.username || url.password || !basePath || url.search || url.hash) {
      return Promise.reject(new Error('仅填写基础网关，例如 https://api.vidu.cn'));
    }
    return Promise.resolve();
  } catch {
    return Promise.reject(new Error('请输入合法的 HTTP/HTTPS 基础网关'));
  }
};

/** 表单字段 → 所在 Tab，用于校验失败时自动切换到出错的页签 */
const FIELD_TAB_MAP: Record<string, string> = {
  providerName: 'basic', providerCode: 'basic', logoUrl: 'basic', status: 'basic',
  baseUrl: 'basic', apiKey: 'basic', apiSecret: 'basic', apiKeyApplyUrl: 'basic',
  officialDocUrl: 'basic', remark: 'basic', providerCategory: 'basic', displayOrder: 'basic',
  integrationType: 'basic', newApiAccessToken: 'basic', newApiUserId: 'basic',
  taskQuerySuffix: 'schedule',
  callbackBaseUrl: 'schedule',
  authHeader: 'advanced', authPrefix: 'advanced', extraHeaders: 'advanced',
  extraBody: 'advanced', extraQuery: 'advanced'
};

export default function ProviderDialog({ open, title, data, onCancel, onOk }: Props) {
  const [form] = Form.useForm();
  const [loading, setLoading] = useState(false);
  const [strategy, setStrategy] = useState<ScheduleStrategy>(makeDefaultScheduleStrategy());
  /** 思考模式控制（默认 auto 关闭） */
  const [activeTab, setActiveTab] = useState('basic');

  // 监听 providerCode 让 thinking 自动预览
  const providerCode = Form.useWatch('providerCode', form);
  const uploadedLogo = Form.useWatch('logoUrl', form);
  const normalizedProviderCode = normalizeCallbackProviderCode(providerCode);
  const isTokenDance = normalizedProviderCode === 'tokendance';
  const isNewApi = Form.useWatch('integrationType', form) === 'NEW_API';
  const useSystemToken = Form.useWatch('newApiSystemTokenEnabled', form);
  const callbackPath = resolveProviderCallbackPath(providerCode);
  const callbackExample = `https://api.example.com${callbackPath}`;
  // 监听服务商名 / 文档链接 / 申请链接（用于头部展示与按钮）
  const providerName = Form.useWatch('providerName', form);
  const officialDocUrl = Form.useWatch('officialDocUrl', form);
  const apiKeyApplyUrl = Form.useWatch('apiKeyApplyUrl', form);

  useEffect(() => {
    if (!open) return;
    form.resetFields();
    form.setFieldsValue({ providerCategory: providerCategory(data), displayOrder: data?.displayOrder ?? 100,
      integrationType: data?.integrationType || 'NATIVE', newApiSystemTokenEnabled: !!data?.newApiSystemTokenEnabled });
    const s = makeDefaultScheduleStrategy();
    if (data) {
      const { apiKey: _ak, apiSecret: _as, newApiAccessToken: _nt, ...safeData } = data;
      form.setFieldsValue(safeData);
      if (data.scheduleStrategyJson) {
        try { Object.assign(s, JSON.parse(data.scheduleStrategyJson)); } catch {}
      }
      s.supportsCallback = !!data.supportsCallback;
      form.setFieldValue('callbackBaseUrl', s.callbackBaseUrl || undefined);
    }
    setStrategy(s);
    setActiveTab('basic');
  }, [open, data, form]);

  const handleOk = async () => {
    if (loading) return;
    let values: any;
    try {
      values = await form.validateFields();
    } catch (e: any) {
      // 校验失败：自动切换到包含首个错误字段的页签，行为与原先一致（阻断提交）
      const firstErr = e?.errorFields?.[0];
      const name = Array.isArray(firstErr?.name) ? firstErr.name[0] : null;
      if (name && FIELD_TAB_MAP[name]) setActiveTab(FIELD_TAB_MAP[name]);
      return;
    }
    setLoading(true);
    try {
      values.baseUrl = String(values.baseUrl || '').trim().replace(/\/$/, '');
      if (values.taskQuerySuffix) {
        values.taskQuerySuffix = normalizeRelativeEndpoint(values.taskQuerySuffix, true);
      } else {
        values.taskQuerySuffix = '';
      }
      values.supportsCallback = strategy.supportsCallback;
      const strategyToSave: any = { ...strategy };
      const callbackBaseUrl = String(values.callbackBaseUrl || '').trim();
      if (callbackBaseUrl) {
        strategyToSave.callbackBaseUrl = callbackBaseUrl;
      } else {
        delete strategyToSave.callbackBaseUrl;
      }
      delete values.callbackBaseUrl;
      // 并发上限留空/<=0 视为不限制，不写入 JSON，避免持久化 null
      if (strategyToSave.maxConcurrency == null || Number(strategyToSave.maxConcurrency) <= 0) {
        delete strategyToSave.maxConcurrency;
      } else {
        strategyToSave.maxConcurrency = Number(strategyToSave.maxConcurrency);
      }
      // 无进展超时留空/<=0 不写入 JSON，由后端回落最大存活（等价于拆分前的单时钟）
      if (strategyToSave.progressTimeoutSeconds == null || Number(strategyToSave.progressTimeoutSeconds) <= 0) {
        delete strategyToSave.progressTimeoutSeconds;
      } else {
        strategyToSave.progressTimeoutSeconds = Number(strategyToSave.progressTimeoutSeconds);
      }
      // 清除已废弃的并发键：并发上限统一收口到 maxConcurrency，
      // 若不删则历史 JSON 里的旧键会被原样回写，留下"看着有配置但没人读"的死数据
      delete strategyToSave.providerConcurrency;
      delete strategyToSave.modelConcurrency;
      values.scheduleStrategyJson = JSON.stringify(strategyToSave);

      // 校验 JSON 字段：JsonObjectEditor 输出的已是合法 JSON 字符串或 null
      // 这里只做空值兜底（为防御性兼容外部传入异常值，仍保留 try）
      for (const k of ['extraHeaders', 'extraBody', 'extraQuery'] as const) {
        const v = values[k];
        if (!v || !String(v).trim()) {
          values[k] = null;
          continue;
        }
        try {
          const p = JSON.parse(v);
          if (typeof p !== 'object' || Array.isArray(p) || p === null) {
            values[k] = null;
          }
        } catch {
          values[k] = null;
        }
      }

      // 编辑场景下，若用户未输入新的 apiKey/apiSecret，则不传该字段
      const isEdit = !!data?.id;
      if (isEdit) {
        if (!values.apiKey) delete values.apiKey;
        if (!values.apiSecret) delete values.apiSecret;
        if (!values.newApiAccessToken) delete values.newApiAccessToken;
      }
      if (isNewApi) {
        values.providerCategory = 'AGGREGATOR';
        values.authHeader = 'Authorization';
        values.authPrefix = 'Bearer ';
      }
      const { apiKey: _ak, apiSecret: _as, extraHeaders: _xh, newApiAccessToken: _nt, ...safeOriginal } = (data || {}) as any;
      await onOk({ ...safeOriginal, ...values });
    } finally { setLoading(false); }
  };

  const S = (key: keyof ScheduleStrategy, val: any) => setStrategy((p) => ({ ...p, [key]: val }));

  /** 各页签内容统一滚动容器，避免弹窗整体过高 */
  const tabBodyStyle: React.CSSProperties = { maxHeight: '56vh', overflowY: 'auto', paddingRight: 8, paddingTop: 4 };

  return (
    <Modal open={open} title={title} onCancel={onCancel} onOk={handleOk} confirmLoading={loading} width={920} destroyOnClose maskClosable={false}>
      {/* 头部：服务商名（大字号）+ 官方文档链接 */}
      {(providerName || officialDocUrl) && (
        <div
          style={{
            display: 'flex',
            alignItems: 'baseline',
            gap: 16,
            padding: '2px 0 12px',
            borderBottom: '1px solid rgba(15, 23, 42, 0.06)',
            marginBottom: 4
          }}
        >
          {providerName && (
            <span style={{ fontSize: 18, fontWeight: 600, color: '#1f2937' }}>
              {providerName}
            </span>
          )}
          {officialDocUrl && (
            <Button
              type="link"
              size="small"
              icon={<LinkOutlined />}
              href={officialDocUrl}
              target="_blank"
              rel="noopener noreferrer"
              style={{ padding: 0 }}
            >
              官方文档
            </Button>
          )}
        </div>
      )}
      <Form form={form} layout="vertical" style={{ marginTop: 4 }}>
        <Tabs
          activeKey={activeTab}
          onChange={setActiveTab}
          items={[
            {
              key: 'basic',
              label: '基本信息',
              forceRender: true,
              children: (
                <div style={tabBodyStyle}>
                  {!data?.id && (
                    <Alert
                      type="info"
                      showIcon
                      style={{ marginBottom: 16 }}
                      message="TokenDance 已作为聚合供应商内置"
                      description="无需重复新增。请在左侧三方聚合分区选择 TokenDance，进入目录或账户；此处用于添加其他供应商。"
                    />
                  )}
                  {isTokenDance && (
                    <Alert
                      type="success"
                      showIcon
                      style={{ marginBottom: 16 }}
                      message="TokenDance 官方供应商配置"
                      description="API Key 由保存后的“账户”入口通过 S256 PKCE 安全授权；协议列表由本地模型目录逐模型展示和选择，无需在这里手写协议或 JSON。"
                    />
                  )}
                  <Row gutter={16}>
                    <Col span={24}><Form.Item name="integrationType" label="接入方式"><Select disabled={isTokenDance} options={[{ value: 'NATIVE', label: '原有供应商协议' }, { value: 'NEW_API', label: 'New API 站点' }]} onChange={(value) => { if (value === 'NEW_API') form.setFieldValue('providerCategory', 'AGGREGATOR'); }} /></Form.Item></Col>
                    <Col span={12}><Form.Item name="providerCategory" label="供应商分类" rules={[{ required: true }]}><Select disabled={isNewApi} options={PROVIDER_CATEGORIES.map(({ value, label }) => ({ value, label }))} /></Form.Item></Col>
                    <Col span={12}><Form.Item name="displayOrder" label="展示排序" tooltip="数字越小越靠前，仅影响同类供应商的显示顺序。"><InputNumber min={0} max={9999} precision={0} style={{ width: '100%' }} /></Form.Item></Col>
                    <Col span={12}><Form.Item name="providerName" label="服务商名称" rules={[{ required: true }]}><Input placeholder="如: 字节火山引擎" /></Form.Item></Col>
                    <Col span={12}><Form.Item name="providerCode" label="服务商编码" rules={[{ required: true }]} tooltip={isTokenDance ? 'TokenDance 的稳定路由编码，创建后不可修改。' : '系统内路由标识，volcengine / dashscope / openai 等'}><Input disabled={!!data?.id && isTokenDance} placeholder="如: bytedance" /></Form.Item></Col>
                    <Col span={24}>
                      <Form.Item
                        name="logoUrl"
                        label="服务商 LOGO"
                        extra={!uploadedLogo && resolveProviderLogo(providerCode) ? <span style={{ display: 'inline-flex', alignItems: 'center', gap: 8 }}><img src={resolveProviderLogo(providerCode)} alt="内置服务商图标" width={24} height={24} style={{ objectFit: 'contain' }} />未上传时使用内置图标</span> : '上传后使用自定义图标；清空后恢复内置图标。'}
                        tooltip="厂家品牌图标，左侧服务商列表与模型相关接口会带出展示。上传方式（本地/OSS/COS）由系统配置自动决定。"
                      >
                        <ImageUpload
                          maxCount={1}
                          maxSize={5}
                          accept="image/*"
                        />
                      </Form.Item>
                    </Col>
                    <Col span={12}><Form.Item name="status" label="状态"><Select options={ENABLE_STATUS_OPTIONS.map((o) => ({ label: o.label, value: o.value }))} /></Form.Item></Col>
                    <Col span={24}>
                      <Form.Item
                        name="baseUrl"
                        label="API 网关地址"
                        tooltip="只能填写协议、域名和可选端口；/v1、/api/v3 等版本路径请填写到对应模型的接口路径中。"
                        rules={[
                          { required: true, message: '请输入API基础网关地址' },
                          { validator: validateBaseGatewayUrl }
                        ]}
                      >
                        <Input placeholder="https://api.vidu.cn" />
                      </Form.Item>
                    </Col>
                    <Col span={12}>
                      {isTokenDance ? (
                        <Form.Item label="API 密钥">
                          <Input disabled value="保存供应商后，通过“账户”入口 OAuth 授权" />
                        </Form.Item>
                      ) : (
                        <>
                          <Form.Item name="apiKey" label="API 密钥" rules={[{ required: (!data?.id && !(isNewApi && useSystemToken))
                            || (isNewApi && !useSystemToken && !!data?.newApiSystemTokenEnabled && !data?.newApiTokenId), message: '请输入模型 API Key' }]}
                            extra={isNewApi && useSystemToken ? '可暂不填写；保存后在“账户与模型”中选择或创建本站 API Key。' : undefined}>
                            <Input.Password placeholder={data?.id ? '已配置（不显示，留空不修改）' : isNewApi && useSystemToken ? '选填，保存后可从上游账户绑定' : '官方 API 密钥(加密存储)'} visibilityToggle={false} />
                          </Form.Item>
                          {apiKeyApplyUrl && (
                            <Button
                              type="link"
                              size="small"
                              style={{ padding: 0, marginTop: -12, marginBottom: 8 }}
                              onClick={() => window.open(apiKeyApplyUrl, '_blank', 'noopener,noreferrer')}
                            >
                              去申请 →
                            </Button>
                          )}
                        </>
                      )}
                    </Col>
                    <Col span={12}><Form.Item name="apiSecret" label="扩展密钥" tooltip={normalizedProviderCode === 'kling' ? '可灵启用回调优先时必须填写 whsec_ Webhook Secret；轮换期可用逗号或换行分隔多个。' : '按供应商协议填写。Webhook 服务商可在此填写签名密钥。'} rules={[{ required: !data?.id && normalizedProviderCode === 'kling' && strategy.supportsCallback && strategy.dispatchMode === 'CALLBACK_FIRST', message: '可灵回调优先模式必须填写 whsec_ 签名密钥' }]}><Input.Password placeholder={data?.id ? '已配置则留空不修改' : '扩展密钥(选填)'} visibilityToggle={false} /></Form.Item></Col>
                    <Col span={12}>
                      <Form.Item
                        name="apiKeyApplyUrl"
                        label="API Key 申请页"
                        rules={[{ type: 'url', message: '请输入合法的 URL' }]}
                        tooltip="填写后 API 密钥下方出现「去申请 →」按钮"
                      >
                        <Input placeholder="https://console.example.com/apikey" allowClear />
                      </Form.Item>
                    </Col>
                    <Col span={12}>
                      <Form.Item
                        name="officialDocUrl"
                        label="官方文档地址"
                        rules={[{ type: 'url', message: '请输入合法的 URL' }]}
                        tooltip="填写后弹窗头部显示「官方文档」链接"
                      >
                        <Input placeholder="https://docs.example.com" allowClear />
                      </Form.Item>
                    </Col>
                    {isNewApi && <>
                      <Col span={24}><Form.Item name="newApiSystemTokenEnabled" label="使用系统访问令牌" valuePropName="checked" extra="关闭时只使用模型 API Key，手动添加模型；开启后可读取本站账户分组及动态模型目录。"><Switch /></Form.Item></Col>
                      {useSystemToken && <>
                        <Col span={24}><Alert type="info" showIcon message="使用普通用户访问令牌即可，无需账号密码" description="先在上游站点完成 GitHub 等登录，再从个人设置或安全设置生成访问令牌。该令牌与调用模型的 API Key 不同。" style={{ marginBottom: 16 }} /></Col>
                        <Col span={16}><Form.Item name="newApiAccessToken" label="系统访问令牌" rules={[{ required: !data?.newApiSystemTokenEnabled, message: '请输入上游访问令牌' }]}><Input.Password autoComplete="new-password" visibilityToggle={false} placeholder={data?.newApiSystemTokenEnabled ? '留空保留，变更站点地址须重新填写' : '上游个人设置中的访问令牌'} /></Form.Item></Col>
                        <Col span={8}><Form.Item name="newApiUserId" label="上游用户 ID" tooltip="仅旧版站点要求填写；新版可自动识别。"><InputNumber min={1} precision={0} style={{ width: '100%' }} /></Form.Item></Col>
                      </>}
                    </>}
                    <Col span={24}><Form.Item name="remark" label="备注"><Input.TextArea rows={2} placeholder="备注信息（选填）" /></Form.Item></Col>
                  </Row>
                </div>
              )
            },
            {
              key: 'schedule',
              label: '调度策略',
              forceRender: true,
              children: (
                <div style={tabBodyStyle}>
                  <Row gutter={16}>
                    <Col span={8}><Form.Item label="支持回调"><Switch checked={strategy.supportsCallback} onChange={(v) => S('supportsCallback', v)} /></Form.Item></Col>
                    <Col span={8}><Form.Item label="调度模式"><Select value={strategy.dispatchMode} onChange={(v) => S('dispatchMode', v)} options={DISPATCH_MODE_OPTIONS.map((o) => ({ label: o.label, value: o.value }))} /></Form.Item></Col>
                  </Row>
                  {strategy.supportsCallback && (
                    <Row gutter={16}>
                      <Col span={24}>
                        <Form.Item
                          name="callbackBaseUrl"
                          label="回调地址"
                          tooltip={normalizedProviderCode === 'kling'
                            ? `可灵仅接受公网 HTTPS 回调地址，例如：${callbackExample}`
                            : normalizedProviderCode === 'minimax'
                              ? `MiniMax H3 仅接受公网 HTTPS 回调地址；可留空并自动轮询。例如：${callbackExample}`
                              : `填写厂商可访问的完整公网回调地址，例如：${callbackExample}`}
                          rules={[
                            {
                              required: normalizedProviderCode === 'vidu'
                                || (normalizedProviderCode === 'kling'
                                  && strategy.supportsCallback && strategy.dispatchMode === 'CALLBACK_FIRST'),
                              message: normalizedProviderCode === 'kling'
                                ? '可灵回调优先模式必须填写回调地址'
                                : 'Vidu 开启回调时必须填写回调地址'
                            },
                            {
                              type: 'url',
                              transform: (value) => (typeof value === 'string' ? value.trim() : value),
                              message: normalizedProviderCode === 'kling' || normalizedProviderCode === 'minimax'
                                ? '请输入完整的 HTTPS 地址'
                                : '请输入完整的 HTTP/HTTPS 地址'
                            },
                            {
                              validator: async (_, value) => {
                                const validationError = validateProviderCallbackUrl(normalizedProviderCode, value);
                                if (validationError) throw new Error(validationError);
                              }
                            }
                          ]}
                        >
                          <Input
                            prefix={<LinkOutlined />}
                            placeholder={callbackExample}
                            allowClear
                          />
                        </Form.Item>
                      </Col>
                    </Row>
                  )}
                  <Row gutter={16}>
                    <Col span={24}>
                      <Form.Item
                        name="taskQuerySuffix"
                        label="任务查询路径模板"
                        tooltip="异步模型的只读查询相对路径，包含完整版本前缀，并用唯一的 %s 表示任务号。同步模型可留空。"
                        rules={[{ validator: validateRelativeEndpoint(true) }]}
                      >
                        <Input placeholder="如 /api/v3/contents/generations/tasks/%s" maxLength={500} allowClear />
                      </Form.Item>
                    </Col>
                    <Col span={6}><Form.Item label="首次延迟(秒)"><InputNumber value={strategy.firstPollDelaySeconds} onChange={(v) => S('firstPollDelaySeconds', v)} min={1} max={600} style={{ width: '100%' }} /></Form.Item></Col>
                    <Col span={6}><Form.Item label="基础间隔(秒)"><InputNumber value={strategy.baseIntervalSeconds} onChange={(v) => S('baseIntervalSeconds', v)} min={1} max={600} style={{ width: '100%' }} /></Form.Item></Col>
                    <Col span={6}><Form.Item label="最大间隔(秒)"><InputNumber value={strategy.maxIntervalSeconds} onChange={(v) => S('maxIntervalSeconds', v)} min={1} max={600} style={{ width: '100%' }} /></Form.Item></Col>
                    <Col span={6}><Form.Item label="退避系数"><InputNumber value={strategy.backoffFactor} onChange={(v) => S('backoffFactor', v)} min={1} max={5} step={0.1} style={{ width: '100%' }} /></Form.Item></Col>
                  </Row>
                  <Row gutter={16}>
                    <Col span={6}>
                      <Form.Item
                        label="退避次数上限"
                        tooltip="只控制轮询间隔增长，达到上限后保持最大间隔继续轮询，不作为任务判死条件。"
                      >
                        <InputNumber value={strategy.maxRetryCount} onChange={(v) => S('maxRetryCount', v)} min={1} max={999} style={{ width: '100%' }} />
                      </Form.Item>
                    </Col>
                    <Col span={6}>
                      <Form.Item
                        label="最大存活(秒)"
                        tooltip="绝对天花板，从上游受理该任务那一刻起算，只用于防止上游永远回报处理中而长期占用并发坑位，不是预期出片耗时。配小了会在上游正常出片时把任务掐掉、积分照扣成片丢弃，建议 3600 起。"
                      >
                        <InputNumber value={strategy.maxLifeSeconds} onChange={(v) => S('maxLifeSeconds', v)} min={60} max={7200} style={{ width: '100%' }} />
                      </Form.Item>
                    </Col>
                    <Col span={6}>
                      <Form.Item
                        label="无进展超时(秒)"
                        tooltip="判定上游生死的依据：从最近一次观测到上游推进（轮询拿到处理中、回调带回非终态）起算，连续这么久观测不到推进才判失败并退款。应大于最大轮询间隔的数倍，留空 = 回落最大存活。"
                      >
                        <InputNumber
                          value={strategy.progressTimeoutSeconds ?? undefined}
                          onChange={(v) => S('progressTimeoutSeconds', v)}
                          min={60}
                          max={7200}
                          style={{ width: '100%' }}
                          placeholder="留空=回落最大存活"
                        />
                      </Form.Item>
                    </Col>
                    <Col span={6}>
                      <Form.Item
                        label="供应商并发上限"
                        tooltip="该供应商（同一 API Key）下所有模型同时在途的上游请求总数上限，即厂商任务列表里同时处理中的任务数。留空 = 不限（仍受全局上限约束）。保存时会校验：不得超过全局上限，也不得小于其下任一模型已配的上限。"
                      >
                        <InputNumber
                          value={strategy.maxConcurrency ?? undefined}
                          onChange={(v) => S('maxConcurrency', v)}
                          min={1}
                          max={1000}
                          controls={false}
                          style={{ width: '100%' }}
                          placeholder="留空=不限制"
                        />
                      </Form.Item>
                    </Col>
                  </Row>
                </div>
              )
            },
            {
              key: 'advanced',
              label: '高级配置',
              forceRender: true,
              children: (
                <div style={tabBodyStyle}>
                  <Alert
                    type="info"
                    showIcon
                    style={{ marginBottom: 16 }}
                    message="鉴权与扩展参数"
                    description="常规厂商无需配置此处。仅当厂商要求特殊鉴权头或附加请求参数时，通过下方可视化键值编辑器添加。"
                  />
                  <Row gutter={16}>
                    <Col span={12}>
                      <Form.Item
                        name="authHeader"
                        label="鉴权 Header 名"
                        tooltip="OpenAI / 大多数厂商：Authorization；Azure OpenAI：api-key"
                      >
                        <Input placeholder="Authorization（默认）" />
                      </Form.Item>
                    </Col>
                    <Col span={12}>
                      <Form.Item
                        name="authPrefix"
                        label="鉴权前缀"
                        tooltip='OpenAI: "Bearer "；Azure: 留空（直接放裸 token）'
                      >
                        <Input placeholder='"Bearer "（默认；空字符串=无前缀）' />
                      </Form.Item>
                    </Col>
                    <Col span={24}>
                      <Form.Item
                        name="extraHeaders"
                        label="自定义 Headers"
                        tooltip="如 Azure OpenAI 需要 api-version；存储为 JSON 对象，list 接口不回显"
                      >
                        <JsonObjectEditor
                          presets={EXTRA_HEADERS_PRESETS}
                          stringOnly
                          emptyText="未配置自定义 Header"
                        />
                      </Form.Item>
                    </Col>
                    <Col span={24}>
                      <Form.Item
                        name="extraBody"
                        label="请求体附加参数"
                        tooltip='思考模式控制建议用「思考模式」页签的开关，不要手动写。这里放其它厂商特殊参数。'
                      >
                        <JsonObjectEditor
                          presets={EXTRA_BODY_PRESETS}
                          emptyText="未配置请求体附加参数"
                        />
                      </Form.Item>
                    </Col>
                    <Col span={24}>
                      <Form.Item
                        name="extraQuery"
                        label="自定义 Query String"
                        tooltip="部分厂商需要在 URL 上附加查询参数（如百度千帆早期版本）"
                      >
                        <JsonObjectEditor
                          presets={EXTRA_QUERY_PRESETS}
                          stringOnly
                          emptyText="未配置 Query 参数"
                        />
                      </Form.Item>
                    </Col>
                  </Row>
                </div>
              )
            }
          ]}
        />
      </Form>
    </Modal>
  );
}
