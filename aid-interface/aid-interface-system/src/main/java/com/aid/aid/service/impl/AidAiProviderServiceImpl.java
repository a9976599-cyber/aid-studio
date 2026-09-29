package com.aid.aid.service.impl;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import cn.hutool.core.util.StrUtil;
import com.aid.aid.domain.AidAiProvider;
import com.aid.aid.domain.AidAiModel;
import com.aid.aid.mapper.AidAiModelMapper;
import com.aid.aid.mapper.AidAiProviderMapper;
import com.aid.aid.service.IAidAiProviderService;
import com.aid.common.exception.ServiceException;
import com.aid.common.utils.DateUtils;
import com.aid.common.utils.GatewayUrlUtils;
import com.aid.common.utils.ProviderEndpointUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import cn.hutool.json.JSONUtil;
import java.net.URI;
import java.util.Base64;

/**
 * AI大模型服务商(官方渠道)配置Service业务层处理
 *
 * @author 视觉AID
 */
@Slf4j
@Service
public class AidAiProviderServiceImpl extends ServiceImpl<AidAiProviderMapper, AidAiProvider> implements IAidAiProviderService
{
    @org.springframework.beans.factory.annotation.Autowired
    private AidAiModelMapper modelMapper;
    private static final String KLING_PROVIDER_CODE = "kling";
    /** 启用状态 */
    private static final String STATUS_ENABLED = "0";

    /** 停用状态 */
    private static final String STATUS_DISABLED = "1";

    /**
     * 查询AI大模型服务商(官方渠道)配置
     *
     * @param id AI大模型服务商(官方渠道)配置主键
     * @return AI大模型服务商(官方渠道)配置
     */
    @Override
    public AidAiProvider selectAidAiProviderById(Long id)
    {
        return this.getById(id);
    }

    /**
     * 查询AI大模型服务商(官方渠道)配置列表
     *
     * @param aidAiProvider AI大模型服务商(官方渠道)配置
     * @return AI大模型服务商(官方渠道)配置
     */
    @Override
    public List<AidAiProvider> selectAidAiProviderList(AidAiProvider aidAiProvider)
    {
        LambdaQueryWrapper<AidAiProvider> wrapper = Wrappers.lambdaQuery();
        if (aidAiProvider != null)
        {
            if (StrUtil.isNotBlank(aidAiProvider.getProviderName()))
            {
                wrapper.like(AidAiProvider::getProviderName, aidAiProvider.getProviderName());
            }
            if (StrUtil.isNotBlank(aidAiProvider.getProviderCode()))
            {
                wrapper.like(AidAiProvider::getProviderCode, aidAiProvider.getProviderCode());
            }
            if (StrUtil.isNotBlank(aidAiProvider.getStatus()))
            {
                wrapper.eq(AidAiProvider::getStatus, aidAiProvider.getStatus());
            }
        }
        wrapper.orderByDesc(AidAiProvider::getId);
        return this.list(wrapper);
    }

    /**
     * 新增AI大模型服务商(官方渠道)配置
     *
     * @param aidAiProvider AI大模型服务商(官方渠道)配置
     * @return 结果
     */
    @Override
    public int insertAidAiProvider(AidAiProvider aidAiProvider)
    {
        normalizePresentation(aidAiProvider, true);
        normalizeIntegration(aidAiProvider, null);
        validateAndNormalizeBaseUrl(aidAiProvider);
        validateAndNormalizeTaskQuerySuffix(aidAiProvider);
        validateKlingConfiguration(aidAiProvider);
        aidAiProvider.setCreateTime(DateUtils.getNowDate());
        return this.save(aidAiProvider) ? 1 : 0;
    }

    /**
     * 修改AI大模型服务商(官方渠道)配置
     *
     * @param aidAiProvider AI大模型服务商(官方渠道)配置
     * @return 结果
     */
    @Override
    public int updateAidAiProvider(AidAiProvider aidAiProvider)
    {
        normalizePresentation(aidAiProvider, false);
        validateAndNormalizeBaseUrl(aidAiProvider);
        validateAndNormalizeTaskQuerySuffix(aidAiProvider);
        AidAiProvider before = aidAiModelSafeLoad(aidAiProvider == null ? null : aidAiProvider.getId());
        if (before != null && aidAiProvider.getIntegrationType() != null
                && !Objects.equals(before.getIntegrationType(), aidAiProvider.getIntegrationType())
                && modelMapper.selectCount(Wrappers.<AidAiModel>lambdaQuery()
                        .eq(AidAiModel::getProviderId, before.getId()).eq(AidAiModel::getDelFlag, "0")) > 0) {
            throw new ServiceException("此供应商已有模型，变更接入方式请新建供应商");
        }
        if (before != null && "NEW_API".equals(before.getIntegrationType())
                && !Objects.equals(before.getBaseUrl(), aidAiProvider.getBaseUrl())
                && modelMapper.selectCount(Wrappers.<AidAiModel>lambdaQuery()
                        .eq(AidAiModel::getProviderId, before.getId()).eq(AidAiModel::getDelFlag, "0")) > 0) {
            throw new ServiceException("此站点已有模型，变更地址请新建供应商并重新验证模型");
        }
        normalizeIntegration(aidAiProvider, before);
        validateKlingConfiguration(mergeProviderForValidation(before, aidAiProvider));
        aidAiProvider.setUpdateTime(DateUtils.getNowDate());
        return this.updateById(aidAiProvider) ? 1 : 0;
    }

    /**
     * 修改AI大模型服务商启停状态。
     * 状态切换只更新状态及审计字段，避免把局部请求误当完整服务商配置校验。
     *
     * @param id 服务商主键
     * @param status 目标状态：0启用，1停用
     * @param updateBy 更新者
     * @return 结果
     */
    @Override
    public int updateAidAiProviderStatus(Long id, String status, String updateBy)
    {
        if (Objects.isNull(id))
        {
            log.error("修改服务商状态失败, 服务商主键为空");
            throw new ServiceException("主键不能为空");
        }
        if (!Objects.equals(STATUS_ENABLED, status) && !Objects.equals(STATUS_DISABLED, status))
        {
            log.error("修改服务商状态失败, 状态非法, id={}, status={}", id, status);
            throw new ServiceException("状态参数错误");
        }
        AidAiProvider updateProvider = new AidAiProvider();
        updateProvider.setId(id);
        updateProvider.setStatus(status);
        updateProvider.setUpdateTime(DateUtils.getNowDate());
        updateProvider.setUpdateBy(updateBy);
        return this.updateById(updateProvider) ? 1 : 0;
    }

    /**
     * 批量删除AI大模型服务商(官方渠道)配置
     *
     * @param ids 需要删除的AI大模型服务商(官方渠道)配置主键
     * @return 结果
     */
    @Override
    public int deleteAidAiProviderByIds(Long[] ids)
    {
        if (ids == null || ids.length == 0)
        {
            return 0;
        }
        return this.removeByIds(Arrays.asList(ids)) ? 1 : 0;
    }

    /**
     * 删除AI大模型服务商(官方渠道)配置信息
     *
     * @param id AI大模型服务商(官方渠道)配置主键
     * @return 结果
     */
    @Override
    public int deleteAidAiProviderById(Long id)
    {
        if (id == null)
        {
            return 0;
        }
        return this.removeById(id) ? 1 : 0;
    }

    /**
     * 校验并规范化服务商基础网关地址。
     *
     * @param aidAiProvider 服务商配置
     */
    private void validateAndNormalizeBaseUrl(AidAiProvider aidAiProvider)
    {
        if (Objects.isNull(aidAiProvider) || StrUtil.isBlank(aidAiProvider.getBaseUrl()))
        {
            log.error("保存服务商失败, API基础网关为空");
            throw new ServiceException("网关地址不能为空");
        }
        String baseUrl = aidAiProvider.getBaseUrl().trim();
        if (!GatewayUrlUtils.isBaseGatewayUrl(baseUrl))
        {
            log.error("保存服务商失败, API网关不是基础地址, baseUrl={}", baseUrl);
            throw new ServiceException("请填写基础网关");
        }
        aidAiProvider.setBaseUrl(GatewayUrlUtils.normalizeBaseGatewayUrl(baseUrl));
    }

    /** 校验并规范化供应商异步任务查询路径。 */
    private void validateAndNormalizeTaskQuerySuffix(AidAiProvider aidAiProvider)
    {
        if (Objects.isNull(aidAiProvider) || StrUtil.isBlank(aidAiProvider.getTaskQuerySuffix()))
        {
            return;
        }
        try
        {
            aidAiProvider.setTaskQuerySuffix(ProviderEndpointUtils.normalizeTaskQueryTemplate(
                    aidAiProvider.getTaskQuerySuffix()));
        }
        catch (IllegalArgumentException ex)
        {
            log.error("保存服务商失败, 查询路径无效, providerCode={}, reason={}",
                    aidAiProvider.getProviderCode(), ex.getMessage());
            throw new ServiceException("查询路径无效");
        }
    }

    private void normalizeIntegration(AidAiProvider provider, AidAiProvider before)
    {
        String type = provider.getIntegrationType() != null ? provider.getIntegrationType()
                : before == null ? "NATIVE" : before.getIntegrationType();
        if (type == null) type = "NATIVE";
        if (!"NATIVE".equals(type) && !"NEW_API".equals(type)) {
            log.info("供应商接入方式无效, providerId={}", provider.getId());
            throw new ServiceException("接入方式无效");
        }
        provider.setIntegrationType(type);
        boolean accountEnabled = "NEW_API".equals(type) && Boolean.TRUE.equals(
                provider.getNewApiSystemTokenEnabled() != null ? provider.getNewApiSystemTokenEnabled()
                        : before == null ? false : before.getNewApiSystemTokenEnabled());
        provider.setNewApiSystemTokenEnabled(accountEnabled);
        // 账户模式可先保存供应商，再从本站普通用户令牌选择或创建模型 Key。
        // api_key 列非空；新建时用空串占位，后续绑定 Key 时覆盖。
        if (before == null && accountEnabled && provider.getApiKey() == null) {
            provider.setApiKey("");
        }
        if (!accountEnabled) {
            if ("NEW_API".equals(type) && StrUtil.isBlank(provider.getApiKey())
                    && (before == null || StrUtil.isBlank(before.getApiKey()))) {
                throw new ServiceException("关闭账户授权时请填写模型调用 Key");
            }
            // 空字符串会实际写入数据库；不影响模型调用 Key 和已有模型。
            provider.setNewApiAccessToken("");
            provider.setNewApiUserId(0L);
        } else {
            boolean changedOrigin = before != null && !Objects.equals(before.getBaseUrl(), provider.getBaseUrl());
            String token = StrUtil.isNotBlank(provider.getNewApiAccessToken()) ? provider.getNewApiAccessToken().trim()
                    : changedOrigin || before == null ? null : before.getNewApiAccessToken();
            if (StrUtil.isBlank(token) || token.length() > 4096 || token.contains("\r") || token.contains("\n")) {
                log.info("New API 缺少有效账户授权, providerId={}", provider.getId());
                throw new ServiceException("请填写访问令牌");
            }
            provider.setNewApiAccessToken(token);
        }
        if ("NEW_API".equals(type)) {
            provider.setProviderCategory("AGGREGATOR");
            provider.setAuthHeader("Authorization");
            provider.setAuthPrefix("Bearer ");
        }
    }

    private void normalizePresentation(AidAiProvider provider, boolean creating)
    {
        if (creating && provider.getProviderCategory() == null) provider.setProviderCategory("AGGREGATOR");
        String category = provider.getProviderCategory();
        if (category != null && !Objects.equals(category, "AGGREGATOR") && !Objects.equals(category, "OFFICIAL")) {
            log.info("供应商展示分类无效, providerId={}", provider.getId());
            throw new ServiceException("供应商分类无效");
        }
        if (creating && provider.getDisplayOrder() == null) provider.setDisplayOrder(100);
        if (provider.getDisplayOrder() != null && (provider.getDisplayOrder() < 0 || provider.getDisplayOrder() > 9999)) {
            log.info("供应商展示排序无效, providerId={}", provider.getId());
            throw new ServiceException("展示排序超出范围");
        }
    }

    private AidAiProvider aidAiModelSafeLoad(Long id)
    {
        return id == null ? null : this.getById(id);
    }

    /** 合并编辑页未回传的 WRITE_ONLY 密钥等字段，仅用于校验，不改变实际更新对象。 */
    private AidAiProvider mergeProviderForValidation(AidAiProvider before, AidAiProvider update)
    {
        if (before == null || update == null)
        {
            return update;
        }
        AidAiProvider effective = new AidAiProvider();
        effective.setProviderCode(StrUtil.isBlank(update.getProviderCode()) ? before.getProviderCode() : update.getProviderCode());
        effective.setBaseUrl(StrUtil.isBlank(update.getBaseUrl()) ? before.getBaseUrl() : update.getBaseUrl());
        effective.setApiSecret(StrUtil.isBlank(update.getApiSecret()) ? before.getApiSecret() : update.getApiSecret());
        effective.setSupportsCallback(update.getSupportsCallback() == null
                ? before.getSupportsCallback() : update.getSupportsCallback());
        effective.setScheduleStrategyJson(StrUtil.isBlank(update.getScheduleStrategyJson())
                ? before.getScheduleStrategyJson() : update.getScheduleStrategyJson());
        return effective;
    }

    /** 可灵选择回调优先时必须同时具备公网地址与合法 whsec_ 密钥。 */
    void validateKlingConfiguration(AidAiProvider provider)
    {
        if (provider == null || !KLING_PROVIDER_CODE.equalsIgnoreCase(StrUtil.trim(provider.getProviderCode())))
        {
            return;
        }
        if (StrUtil.isBlank(provider.getScheduleStrategyJson()))
        {
            return;
        }
        final cn.hutool.json.JSONObject strategy;
        try
        {
            if (!JSONUtil.isTypeJSONObject(provider.getScheduleStrategyJson()))
            {
                throw new IllegalArgumentException("not an object");
            }
            strategy = JSONUtil.parseObj(provider.getScheduleStrategyJson());
        }
        catch (Exception ex)
        {
            log.warn("Kling provider rejected: invalid schedule strategy JSON, providerId={}", provider.getId());
            throw new ServiceException("调度策略格式无效");
        }
        boolean callbackFirst = "CALLBACK_FIRST".equalsIgnoreCase(strategy.getStr("dispatchMode"));
        boolean callbackEnabled = Boolean.TRUE.equals(provider.getSupportsCallback())
                && strategy.getBool("supportsCallback", true);
        if (!callbackFirst || !callbackEnabled)
        {
            return;
        }
        String callbackUrl = strategy.getStr("callbackBaseUrl");
        if (!isValidKlingCallbackUrl(callbackUrl))
        {
            log.warn("Kling provider rejected: invalid callback route, providerId={}", provider.getId());
            throw new ServiceException("回调地址无效");
        }
        if (!hasValidWebhookSecret(provider.getApiSecret()))
        {
            log.warn("Kling provider rejected: invalid webhook secret, providerId={}", provider.getId());
            throw new ServiceException("回调密钥无效");
        }
    }

    private boolean isValidKlingCallbackUrl(String value)
    {
        if (!isValidHttpUrl(value))
        {
            return false;
        }
        URI uri = URI.create(value.trim());
        return "https".equalsIgnoreCase(uri.getScheme())
                && StrUtil.removeSuffix(uri.getPath(), "/").endsWith("/api/media/callback/kling");
    }

    private boolean isValidHttpUrl(String value)
    {
        if (StrUtil.isBlank(value))
        {
            return false;
        }
        try
        {
            URI uri = URI.create(value.trim());
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && StrUtil.isNotBlank(uri.getHost()) && uri.getUserInfo() == null && uri.getFragment() == null;
        }
        catch (Exception ex)
        {
            return false;
        }
    }

    private boolean hasValidWebhookSecret(String secrets)
    {
        if (StrUtil.isBlank(secrets))
        {
            return false;
        }
        for (String raw : secrets.split("[,;\\r\\n]+"))
        {
            String value = StrUtil.trim(raw);
            if (StrUtil.startWith(value, "whsec_"))
            {
                try
                {
                    if (Base64.getDecoder().decode(value.substring("whsec_".length())).length > 0)
                    {
                        return true;
                    }
                }
                catch (IllegalArgumentException ignored)
                {
                    // 检查轮换列表中的下一条。
                }
            }
        }
        return false;
    }
}
