package com.aid.newapi;

import cn.hutool.core.util.StrUtil;
import com.aid.aid.domain.AidAiProvider;
import com.aid.aid.service.IAidAiProviderService;
import com.aid.common.exception.ServiceException;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.security.MessageDigest;
import java.util.HexFormat;
import com.aid.aid.domain.AidAiModel;
import com.aid.aid.service.IAidAiModelService;
import com.aid.model.definition.ModelDefinitionService;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaTextGenerateRequest;
import com.aid.media.provider.ProviderSubmitResult;
import com.aid.media.provider.impl.GenericOpenAiCompatibleTextProviderClient;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 普通用户站点授权、分组和动态目录；不调用上游管理员接口。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NewApiService {
    private final IAidAiProviderService providers;
    private final NewApiClient client;
    private final NewApiModelCompiler compiler;
    private final IAidAiModelService models;
    private final ModelDefinitionService definitions;
    @jakarta.annotation.Resource(name = "genericOpenAiCompatibleTextProviderClient")
    private GenericOpenAiCompatibleTextProviderClient textClient;

    public record Group(String name, String description, BigDecimal ratio) { }
    public record Account(long userId, String username, String group, List<Group> groups) { }
    public record Token(long id, String name, String group, int status, long expiredTime,
                        boolean unlimitedQuota, long remainQuota, boolean modelLimitsEnabled) { }
    public record TokenPage(List<Token> items, int total, int page) { }
    public record Model(String id, String owner, List<String> endpoints, JsonNode pricing) { }
    public record Catalog(String group, BigDecimal groupRatio, List<Model> models, String pricingVersion,
                          List<NewApiModelCompiler.Preview> previews, BigDecimal usdToCny) { }
    public record Selection(String modelId, String fingerprint) { }
    public record ImportResult(List<Long> created, List<Long> existing) { }

    public AidAiProvider provider(Long id, boolean account) {
        AidAiProvider p = id == null ? null : providers.selectAidAiProviderById(id);
        if (p == null || !"NEW_API".equals(p.getIntegrationType())) {
            log.info("New API 供应商不存在, providerId={}", id);
            throw new ServiceException("站点配置不存在");
        }
        if (account && !Boolean.TRUE.equals(p.getNewApiSystemTokenEnabled())) {
            log.info("New API 账户授权关闭, providerId={}", id);
            throw new ServiceException("账户授权未开启");
        }
        return p;
    }

    public Account account(Long id) {
        AidAiProvider p = provider(id, true);
        JsonNode user = client.get(p, "/api/user/self", true).path("data");
        long userId = user.path("id").asLong();
        if (userId <= 0) throw invalid("账户响应无效", id);
        p.setNewApiUserId(userId);
        List<Group> groups = groups(p);
        // 只更新识别出的用户 ID，不把刚读取的旧供应商快照整体覆盖回去。
        providers.lambdaUpdate().eq(AidAiProvider::getId, id)
                .eq(AidAiProvider::getBaseUrl, p.getBaseUrl())
                .eq(AidAiProvider::getNewApiSystemTokenEnabled, true)
                .eq(AidAiProvider::getNewApiAccessToken, p.getNewApiAccessToken())
                .set(AidAiProvider::getNewApiUserId, userId).update();
        return new Account(userId, user.path("username").asText(), user.path("group").asText(), groups);
    }

    private List<Group> groups(AidAiProvider p) {
        JsonNode root = client.get(p, "/api/user/self/groups", true).path("data");
        if (!root.isObject()) throw invalid("分组响应无效", p.getId());
        List<Group> result = new ArrayList<>();
        root.fields().forEachRemaining(e -> result.add(new Group(e.getKey(), e.getValue().path("desc").asText(),
                e.getValue().path("ratio").isNumber() ? e.getValue().path("ratio").decimalValue() : null)));
        return List.copyOf(result);
    }

    public TokenPage tokens(Long id, int page) {
        if (page < 1 || page > 10000) throw invalid("分页参数无效", id);
        AidAiProvider p = provider(id, true);
        JsonNode data = client.get(p, "/api/token/?p=" + page + "&page_size=100", true).path("data");
        // 较早版本直接返回数组；新版返回 items/total 分页对象。
        JsonNode items = data.isArray() ? data : data.path("items");
        if (!items.isArray()) throw invalid("令牌响应无效", id);
        List<Token> result = new ArrayList<>();
        for (JsonNode t : items) result.add(token(t));
        return new TokenPage(List.copyOf(result), data.isArray() ? result.size() : data.path("total").asInt(), page);
    }

    private Token token(JsonNode t) {
        return new Token(t.path("id").asLong(), t.path("name").asText(), t.path("group").asText(),
                t.path("status").asInt(), t.path("expired_time").asLong(), t.path("unlimited_quota").asBoolean(),
                t.path("remain_quota").asLong(), t.path("model_limits_enabled").asBoolean());
    }

    /** 固定名称与供应商行锁共同保证重试不会反复创建上游 Key。 */
    @Transactional(rollbackFor = Exception.class)
    public void createAndBindToken(Long id, String group) {
        providers.getOne(Wrappers.<AidAiProvider>lambdaQuery().eq(AidAiProvider::getId, id).last("FOR UPDATE"));
        AidAiProvider p = provider(id, true);
        ensureGroupImmutable(p, group);
        requireGroup(p, group);
        String suffix;
        try {
            suffix = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(group.getBytes(StandardCharsets.UTF_8))).substring(0, 12);
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw invalid("密钥名称生成失败", id);
        }
        String name = "aid-" + id + "-" + suffix;
        Long tokenId = findDedicatedToken(p, name, group);
        if (tokenId == null) {
            // 超时不自动重试写请求；再次操作时先按确定名称检索已创建的 Key。
            client.post(p, "/api/token/", Map.of("name", name, "group", group,
                    "expired_time", -1, "unlimited_quota", true, "remain_quota", 0,
                    "model_limits_enabled", false));
            tokenId = findDedicatedToken(p, name, group);
        }
        if (tokenId == null) throw invalid("请刷新后检查令牌", id);
        bindToken(id, tokenId, group);
    }

    private Long findDedicatedToken(AidAiProvider p, String name, String group) {
        JsonNode data = client.get(p, "/api/token/search?keyword=" + name + "&p=1&page_size=100", true).path("data");
        JsonNode items = data.isArray() ? data : data.path("items");
        if (!items.isArray()) throw invalid("令牌响应无效", p.getId());
        List<Long> found = new ArrayList<>();
        for (JsonNode item : items) if (name.equals(item.path("name").asText())
                && group.equals(item.path("group").asText())) found.add(item.path("id").asLong());
        if (found.size() > 1) throw invalid("请手动选择令牌", p.getId());
        return found.isEmpty() ? null : found.get(0);
    }

    public void bindToken(Long id, long tokenId, String group) {
        AidAiProvider p = provider(id, true);
        ensureGroupImmutable(p, group);
        requireGroup(p, group);
        JsonNode data = client.get(p, "/api/token/" + tokenId, true).path("data");
        Token t = token(data);
        if (tokenId <= 0 || t.id() != tokenId || t.status() != 1 || !Objects.equals(t.group(), group)
                || (t.expiredTime() > 0 && t.expiredTime() <= System.currentTimeMillis() / 1000)
                || (!t.unlimitedQuota() && t.remainQuota() <= 0)) throw invalid("令牌不可用于此组", id);
        String key = client.post(p, "/api/token/" + tokenId + "/key", Map.of()).path("data").path("key").asText();
        if (StrUtil.isBlank(key) || key.contains("*") || key.length() > 4096) throw invalid("调用密钥无效", id);
        List<String> configuredModels = models.list(Wrappers.<AidAiModel>lambdaQuery()
                        .eq(AidAiModel::getProviderId, id).eq(AidAiModel::getDelFlag, "0"))
                .stream().map(AidAiModel::getRealModelCode).filter(StrUtil::isNotBlank).toList();
        if (!configuredModels.isEmpty()) {
            AidAiProvider proposed = new AidAiProvider();
            proposed.setId(id); proposed.setBaseUrl(p.getBaseUrl()); proposed.setApiKey(key);
            JsonNode available = client.get(proposed, "/v1/models", false).path("data");
            if (!available.isArray()) throw invalid("调用目录响应无效", id);
            Set<String> visible = new LinkedHashSet<>(available.findValuesAsText("id"));
            if (!visible.containsAll(configuredModels))
                throw invalid("此 Key 未覆盖供应商已有模型，请选择其他 Key 或新建站点", id);
        }
        // 官方密钥接口仅返回当前用户自己的 Key。完整 Key 不回传浏览器。
        boolean updated = providers.lambdaUpdate().eq(AidAiProvider::getId, id)
                .eq(AidAiProvider::getBaseUrl, p.getBaseUrl())
                .eq(AidAiProvider::getNewApiSystemTokenEnabled, true)
                .eq(AidAiProvider::getNewApiAccessToken, p.getNewApiAccessToken())
                .set(AidAiProvider::getApiKey, key).set(AidAiProvider::getNewApiTokenId, tokenId)
                .set(AidAiProvider::getNewApiGroup, group).update();
        if (!updated) throw invalid("站点配置已变化", id);
    }

    private void ensureGroupImmutable(AidAiProvider provider, String group) {
        Long id = provider.getId();
        if (StrUtil.isNotBlank(provider.getNewApiGroup()) && !Objects.equals(group, provider.getNewApiGroup())
                && models.count(Wrappers.<AidAiModel>lambdaQuery().eq(AidAiModel::getProviderId, id)
                        .eq(AidAiModel::getDelFlag, "0")) > 0)
            throw invalid("不同分组请新建站点", id);
    }

    private Group requireGroup(AidAiProvider p, String group) {
        if (StrUtil.isBlank(group) || group.length() > 128) throw invalid("请选择可用分组", p.getId());
        return groups(p).stream().filter(g -> g.name().equals(group)).findFirst()
                .orElseThrow(() -> invalid("分组权限已变化", p.getId()));
    }

    public Catalog catalog(Long id, String group) {
        AidAiProvider p = provider(id, true);
        Group selected = requireGroup(p, group);
        JsonNode allowed = client.get(p, "/api/user/models?group=" + URLEncoder.encode(group, StandardCharsets.UTF_8), true).path("data");
        if (!allowed.isArray()) throw invalid("模型目录响应无效", id);
        Set<String> names = new LinkedHashSet<>();
        allowed.forEach(n -> { if (n.isTextual()) names.add(n.asText()); });
        JsonNode pricing = client.getOptionalPricing(p);
        Map<String, JsonNode> prices = new LinkedHashMap<>();
        if (pricing != null) {
            JsonNode items = pricing.path("data");
            if (!items.isArray()) throw invalid("价格目录响应无效", id);
            for (JsonNode item : items) prices.put(item.path("model_name").asText(), item);
        }
        // 绑定同组调用 Key 后，进一步与 Key 的模型白名单取交集。
        Map<String, JsonNode> callable = new LinkedHashMap<>();
        if (Objects.equals(group, p.getNewApiGroup()) && StrUtil.isNotBlank(p.getApiKey())) {
            JsonNode available = client.get(p, "/v1/models", false).path("data");
            if (!available.isArray()) throw invalid("调用目录响应无效", id);
            available.forEach(n -> callable.put(n.path("id").asText(), n));
            names.retainAll(callable.keySet());
        }
        List<Model> result = new ArrayList<>();
        for (String name : names) {
            JsonNode price = prices.get(name);
            JsonNode model = callable.get(name);
            Set<String> endpoints = new LinkedHashSet<>();
            if (price != null) price.path("supported_endpoint_types").forEach(n -> endpoints.add(n.asText()));
            if (model != null) model.path("supported_endpoint_types").forEach(n -> endpoints.add(n.asText()));
            result.add(new Model(name, model == null ? "" : model.path("owned_by").asText(), List.copyOf(endpoints), price));
        }
        JsonNode status = client.get(p, "/api/status", true).path("data");
        BigDecimal exchange = status.path("usd_exchange_rate").isNumber() ? status.path("usd_exchange_rate").decimalValue() : null;
        BigDecimal quota = status.path("quota_per_unit").isNumber() ? status.path("quota_per_unit").decimalValue() : null;
        List<NewApiModelCompiler.Preview> previews = result.stream()
                .map(item -> compiler.compile(id, item, selected.ratio(), exchange, quota)).toList();
        return new Catalog(group, selected.ratio(), List.copyOf(result),
                pricing == null ? "" : pricing.path("pricing_version").asText(), previews, exchange);
    }

    /** 仅有调用 Key 时手动添加文本模型：检查 Key 可见范围并通过本平台协议实际生成。 */
    public void verifyManualModel(AidAiModel model) {
        AidAiProvider p = provider(model.getProviderId(), false);
        boolean text = "text".equals(model.getModelType())
                && "openai-compatible-text".equals(model.getProtocol())
                && "/v1/chat/completions".equals(model.getApiSuffix());
        boolean image = "image".equals(model.getModelType())
                && "newapi-image".equals(model.getProtocol())
                && "/v1/images/generations".equals(model.getApiSuffix());
        if ((!text && !image)
                || StrUtil.isBlank(model.getRealModelCode()) || StrUtil.isBlank(p.getApiKey()))
            throw invalid("请配置 New API 模型协议和调用 Key", p.getId());
        JsonNode available = client.get(p, "/v1/models", false).path("data");
        if (!available.isArray() || !available.findValuesAsText("id").contains(model.getRealModelCode()))
            throw invalid("当前模型 Key 不支持该上游模型", p.getId());
        verifyGeneration(p, model);
    }

    @Transactional(rollbackFor = Exception.class)
    public ImportResult importModels(Long id, String group, List<Selection> selections, String operator) {
        if (selections == null || selections.isEmpty() || selections.size() > 20
                || selections.stream().anyMatch(s -> s == null || StrUtil.isBlank(s.modelId()) || StrUtil.isBlank(s.fingerprint()))
                || selections.stream().map(Selection::modelId).distinct().count() != selections.size())
            throw invalid("模型选择无效", id);
        AidAiProvider p = provider(id, true);
        if (!Objects.equals(group, p.getNewApiGroup()) || p.getNewApiTokenId() == null || StrUtil.isBlank(p.getApiKey()))
            throw invalid("请先绑定分组密钥", id);
        Catalog latest = catalog(id, group);
        Map<String, NewApiModelCompiler.Preview> index = new LinkedHashMap<>();
        latest.previews().forEach(item -> index.put(item.modelId(), item));
        List<AidAiModel> candidates = new ArrayList<>();
        for (Selection selection : selections) {
            var preview = index.get(selection.modelId());
            if (preview == null || preview.model() == null || !preview.blockers().isEmpty()
                    || !Objects.equals(selection.fingerprint(), preview.fingerprint()))
                throw invalid("目录已变请刷新", id);
            candidates.add(preview.model());
        }
        // 新站点的目录和价格只能证明配置存在；实际生成成功后才允许落库并标记能力已验证。
        for (AidAiModel candidate : candidates) {
            if (models.count(Wrappers.<AidAiModel>lambdaQuery().eq(AidAiModel::getProviderId, id)
                    .eq(AidAiModel::getRealModelCode, candidate.getRealModelCode()).eq(AidAiModel::getDelFlag, "0")) == 0) {
                verifyGeneration(p, candidate);
            }
        }
        AidAiProvider locked = providers.getOne(Wrappers.<AidAiProvider>lambdaQuery().eq(AidAiProvider::getId, id).last("FOR UPDATE"));
        if (locked == null || !Objects.equals(locked.getBaseUrl(), p.getBaseUrl())
                || !Objects.equals(locked.getApiKey(), p.getApiKey()) || !Objects.equals(locked.getNewApiGroup(), group)
                || !Boolean.TRUE.equals(locked.getNewApiSystemTokenEnabled())) throw invalid("站点配置已变化", id);
        List<Long> created = new ArrayList<>();
        List<Long> existing = new ArrayList<>();
        for (AidAiModel candidate : candidates) {
            AidAiModel old = models.getOne(Wrappers.<AidAiModel>lambdaQuery().eq(AidAiModel::getProviderId, id)
                    .eq(AidAiModel::getRealModelCode, candidate.getRealModelCode()).eq(AidAiModel::getDelFlag, "0"));
            if (old != null) { existing.add(old.getId()); continue; }
            candidate.setCreateBy(operator);
            definitions.save(candidate, true);
            created.add(candidate.getId());
        }
        return new ImportResult(List.copyOf(created), List.copyOf(existing));
    }

    private void verifyTextGeneration(AidAiProvider provider, AidAiModel model) {
        AiModelConfigVo config = new AiModelConfigVo();
        config.setProviderId(provider.getId()); config.setProviderCode(provider.getProviderCode());
        config.setBaseUrl(provider.getBaseUrl()); config.setApiKey(provider.getApiKey());
        config.setAuthHeader(provider.getAuthHeader()); config.setAuthPrefix(provider.getAuthPrefix());
        config.setModelCode(model.getModelCode()); config.setRealModelCode(model.getRealModelCode());
        config.setProtocol(model.getProtocol()); config.setApiSuffix(model.getApiSuffix());
        config.setCapabilityJson(model.getCapabilityJson());
        MediaTextGenerateRequest request = new MediaTextGenerateRequest();
        request.setModelName(model.getModelCode()); request.setPrompt("Reply with OK.");
        request.setOptions(Map.of("max_completion_tokens", 32));
        ProviderSubmitResult response = textClient.chatSync(config, request);
        if (response == null || StrUtil.isBlank(response.getDirectText()))
            throw invalid("模型 " + model.getRealModelCode() + " 实际生成失败，请核对上游权限和额度", provider.getId());
    }

    private void verifyGeneration(AidAiProvider provider, AidAiModel model) {
        if ("image".equals(model.getModelType()) && "newapi-image".equals(model.getProtocol())) {
            JsonNode result = client.postModel(provider, "/v1/images/generations", Map.of(
                    "model", model.getRealModelCode(), "prompt", "A small blue dot on a white background",
                    "n", 1, "size", "1024x1024"));
            JsonNode images = result.path("data");
            if (!images.isArray() || images.isEmpty()
                    || (images.get(0).path("b64_json").asText().isBlank()
                    && images.get(0).path("url").asText().isBlank()))
                throw invalid("图片实际生成失败", provider.getId());
            return;
        }
        verifyTextGeneration(provider, model);
    }

    private ServiceException invalid(String message, Long id) {
        log.info("New API 配置校验失败, providerId={}, reason={}", id, message);
        return new ServiceException(message);
    }
}
