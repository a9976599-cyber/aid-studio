-- v2.1.9：Topaz Labs 图片处理、供应商分类与内置资源迁移；MySQL 5.7，可重复执行。
SET NAMES utf8mb4;
-- 供应商与模型默认停用；密钥和人民币售价须由站长在后台设置，启用前进行真实上游验证。
INSERT INTO aid_ai_provider
 (provider_name, provider_code, logo_url, base_url, api_key, auth_header, auth_prefix,
  api_key_apply_url, official_doc_url, official_price_url, task_query_suffix,
  status, del_flag, create_time, create_by, remark, supports_callback, schedule_strategy_json)
SELECT 'Topaz Labs', 'topaz', '/brand-icons/topaz.ico', 'https://api.topazlabs.com', '', 'X-API-Key', '',
 'https://www.topazlabs.com/api', 'https://developer.topazlabs.com/',
 'https://developer.topazlabs.com/getting-started/model-pricing.md',
 '/image/v1/status/%s', '1', '0', NOW(), 'system',
 '官方 Image API；按输出像素档位配置人民币 SKU 后启用，不在 SQL 中保存密钥', 0,
 '{"dispatchMode":"POLL_ONLY","supportsCallback":false,"firstPollDelaySeconds":5,"baseIntervalSeconds":5,"maxIntervalSeconds":30,"maxLifeSeconds":3600,"providerConcurrency":10,"modelConcurrency":5}'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM aid_ai_provider WHERE provider_code = 'topaz');

-- 所有模型每次输入一张图、输出一张图，按明确输出宽高计算像素并匹配既有 SKU 账本。
SET @topaz_precision_params = JSON_OBJECT(
 'faceEnhancement', JSON_OBJECT('type','boolean','upstream','face_enhancement'),
 'faceEnhancementStrength', JSON_OBJECT('type','number','min',0,'max',1,'upstream','face_enhancement_strength'),
 'faceEnhancementCreativity', JSON_OBJECT('type','number','min',0,'max',1,'upstream','face_enhancement_creativity'),
 'subjectDetection', JSON_OBJECT('type','string','enum',JSON_ARRAY('foreground','background','all'),'upstream','subject_detection'),
 'sharpen', JSON_OBJECT('type','number','min',0,'max',1),
 'denoise', JSON_OBJECT('type','number','min',0,'max',1),
 'fixCompression', JSON_OBJECT('type','number','min',0,'max',1,'upstream','fix_compression'),
 'strength', JSON_OBJECT('type','number','min',0.01,'max',1),
 'outputFormat', JSON_OBJECT('type','string','enum',JSON_ARRAY('jpeg','jpg','png','tiff','tif'),'upstream','output_format'),
 'cropToFill', JSON_OBJECT('type','boolean','upstream','crop_to_fill'));
SET @topaz_precision_capability = JSON_OBJECT(
 'minReferenceImages',1,'maxReferenceImages',1,'referenceImageMaxPixels',512000000,
 'referenceImageFormats',JSON_ARRAY('jpeg','jpg','png','tiff','tif'),
 'maxOutputPixels',1024000000,'allowCustomWH',true,'requiresConfiguredBilling',true,
 'sceneRules',JSON_OBJECT('imageUpscale',JSON_OBJECT('inputRequirement','image_required')),
 'providerParameters',CAST(@topaz_precision_params AS JSON));

INSERT INTO aid_ai_model
 (provider_id, model_code, real_model_code, model_name, model_type, generate_mode,
  api_suffix, protocol, cost_credits, billing_mode, billing_rule_json, status, del_flag,
  create_time, create_by, remark, image_refine, supports_text_input, supports_image_input,
  supports_multi_image_input, max_output_count, default_output_count, supports_size_preset, supports_aspect_ratio,
  capability_json, capability_inited, official_price_url)
SELECT p.id, catalog.code, catalog.upstream, catalog.label, 'image', 'image_upscale',
 catalog.endpoint, 'topaz-image', 0, 'SKU', NULL, '1', '0', NOW(), 'system',
 'Topaz 官方图片处理模型；未配置密钥、人民币 SKU 和实测前保持停用', 3, catalog.text_input,
 1, 0, 1, 1, 0, 0, catalog.capability, 1,
 'https://developer.topazlabs.com/getting-started/model-pricing.md'
FROM aid_ai_provider p JOIN (
 SELECT 'topaz-standard-2' code, 'Standard V2' upstream, 'Topaz Standard 2' label,
  '/image/v1/enhance/async' endpoint, 0 text_input, @topaz_precision_capability capability
 UNION ALL SELECT 'topaz-low-resolution-2', 'Low Resolution V2', 'Topaz Low Resolution 2',
  '/image/v1/enhance/async', 0, @topaz_precision_capability
 UNION ALL SELECT 'topaz-art-cgi', 'CGI', 'Topaz Art & CGI',
  '/image/v1/enhance/async', 0,
  JSON_SET(@topaz_precision_capability,'$.providerParameters.deblurStrength',
   JSON_OBJECT('type','number','min',0,'max',1))
 UNION ALL SELECT 'topaz-high-fidelity-3', 'Upscale High Fidelity V3', 'Topaz High Fidelity 3',
  '/image/v1/enhance/async', 0,
  JSON_SET(@topaz_precision_capability,
   '$.providerParameters.recoveryStrength',JSON_OBJECT('type','number','min',0,'max',1),
   '$.providerParameters.opacity',JSON_OBJECT('type','number','min',0,'max',1))
 UNION ALL SELECT 'topaz-text-shapes', 'Text Refine', 'Topaz Text & Shapes',
  '/image/v1/enhance/async', 0,
  JSON_SET(@topaz_precision_capability,
   '$.providerParameters.denoiseStrength',JSON_OBJECT('type','number','min',0,'max',1),
   '$.providerParameters.deblurStrength',JSON_OBJECT('type','number','min',0,'max',1),
   '$.providerParameters.decompressionStrength',JSON_OBJECT('type','number','min',0,'max',1),
   '$.providerParameters.opacity',JSON_OBJECT('type','number','min',0,'max',1))
 UNION ALL SELECT 'topaz-wonder-3-5', 'Wonder 3.5', 'Topaz Wonder 3.5',
  '/image/v1/enhance-gen/async', 0,
  JSON_SET(@topaz_precision_capability,'$.maxOutputPixels',256000000,
   '$.providerParameters',JSON_OBJECT(
    'enhancementStrength',JSON_OBJECT('type','string','enum',JSON_ARRAY('low','medium','high')),
    'grain',JSON_OBJECT('type','boolean'),
    'grainDensity',JSON_OBJECT('type','number','min',0,'max',1),
    'grainModel',JSON_OBJECT('type','string','enum',JSON_ARRAY('silver','gaussian','grey')),
    'grainSize',JSON_OBJECT('type','number','min',1,'max',5),
    'grainStrength',JSON_OBJECT('type','number','min',0,'max',1),
    'outputFormat',JSON_OBJECT('type','string','enum',JSON_ARRAY('jpeg','jpg','png','tiff','tif'),'upstream','output_format'),
    'cropToFill',JSON_OBJECT('type','boolean','upstream','crop_to_fill')))
 UNION ALL SELECT 'topaz-bloom-2', 'Bloom 2', 'Topaz Bloom 2',
  '/image/v1/enhance-gen/async', 1,
  JSON_SET(@topaz_precision_capability,'$.maxOutputPixels',256000000,
   '$.promptOptional',true,
   '$.providerParameters',JSON_OBJECT(
    'colorPreservation',JSON_OBJECT('type','boolean'),
    'creativity',JSON_OBJECT('type','integer','min',1,'max',9),
    'grain',JSON_OBJECT('type','boolean'),
    'grainDensity',JSON_OBJECT('type','number','min',0,'max',1),
    'grainModel',JSON_OBJECT('type','string','enum',JSON_ARRAY('silver','gaussian','grey')),
    'grainSize',JSON_OBJECT('type','number','min',1,'max',5),
    'grainStrength',JSON_OBJECT('type','number','min',0,'max',1),
    'seed',JSON_OBJECT('type','integer','min',0,'max',2147483647),
    'outputFormat',JSON_OBJECT('type','string','enum',JSON_ARRAY('jpeg','jpg','png','tiff','tif'),'upstream','output_format'),
    'cropToFill',JSON_OBJECT('type','boolean','upstream','crop_to_fill')))
) catalog ON p.provider_code = 'topaz'
WHERE NOT EXISTS (SELECT 1 FROM aid_ai_model m WHERE m.model_code = catalog.code AND m.del_flag = '0');

-- Topaz 图片按输出像素向上取整计费。参考 Developer 每 Topaz credit $0.10、
-- 估算汇率 ¥6.70/$，初始成本 ¥0.67/credit；实际售价仍乘系统统一倍率，
-- 后台可调整该单价。官方型号页对应 24/8/2 MP 每 credit。
-- 只补齐未定价且仍停用的模型，不覆盖运营自行配置的价格或启停。
UPDATE aid_ai_model m
JOIN aid_ai_provider p ON p.id = m.provider_id
SET m.billing_rule_json = JSON_OBJECT(
 'mode','SKU','meterType','PER_IMAGE','chargeType','IMAGE','preHold',true,
 'matchStrategy','FIRST_HIT','skus',JSON_ARRAY(JSON_OBJECT(
  'skuCode',CONCAT(UPPER(REPLACE(m.model_code,'-','_')),'_OUTPUT_MP'),
  'skuName',CONCAT(m.model_name,' 输出像素计费'),
  'enabled',true,'priority',1,'match',JSON_OBJECT(),
  'price',0.67,
  'outputPixelsPerUnit',CASE
    WHEN m.model_code = 'topaz-wonder-3-5' THEN 8000000
    WHEN m.model_code = 'topaz-bloom-2' THEN 2000000
    ELSE 24000000 END,
  'remark','初始估算：Developer $0.10/credit × ¥6.70/$；按输出像素向上取整，后台可改价'
 )))
WHERE p.provider_code = 'topaz'
  AND m.model_code IN ('topaz-standard-2','topaz-low-resolution-2',
   'topaz-art-cgi','topaz-high-fidelity-3','topaz-text-shapes',
   'topaz-wonder-3-5','topaz-bloom-2')
  AND m.status = '1' AND m.del_flag = '0'
  AND (m.billing_rule_json IS NULL OR TRIM(m.billing_rule_json) = '');


-- Local built-in assets: only replace the shipped defaults, never custom uploads.
UPDATE aid_ai_provider SET logo_url='/brand-icons/dashscope.jpg' WHERE provider_code='dashscope' AND (logo_url IS NULL OR logo_url='' OR logo_url='/aid/2026/07/06/a1c2f4b38230472cb5074382afa97dcc.jpg' OR logo_url LIKE CONCAT('%', '/aid/2026/07/06/a1c2f4b38230472cb5074382afa97dcc.jpg'));
UPDATE aid_ai_provider SET logo_url='/brand-icons/volcengine.jpg' WHERE provider_code='volcengine' AND (logo_url IS NULL OR logo_url='' OR logo_url='/aid/2026/07/06/b0538b7a72444c1ea45cc178d6dd3da1.jpg' OR logo_url LIKE CONCAT('%', '/aid/2026/07/06/b0538b7a72444c1ea45cc178d6dd3da1.jpg'));
UPDATE aid_ai_provider SET logo_url='/brand-icons/jimeng.jpg' WHERE provider_code='jimeng' AND (logo_url IS NULL OR logo_url='' OR logo_url='/aid/2026/07/06/20147fcb2e7348c795fe6c14cf079280.jpg' OR logo_url LIKE CONCAT('%', '/aid/2026/07/06/20147fcb2e7348c795fe6c14cf079280.jpg'));
UPDATE aid_ai_provider SET logo_url='/brand-icons/gemini.png' WHERE provider_code='gemini' AND (logo_url IS NULL OR logo_url='' OR logo_url='/aid/2026/06/28/780b866cf29b41269d455f6bba017ab1.png' OR logo_url LIKE CONCAT('%', '/aid/2026/06/28/780b866cf29b41269d455f6bba017ab1.png'));
UPDATE aid_ai_provider SET logo_url='/brand-icons/openai.png' WHERE provider_code='openai' AND (logo_url IS NULL OR logo_url='' OR logo_url='/aid/2026/06/28/ae4d1a37233c454da5abe3bc6f66840b.png' OR logo_url LIKE CONCAT('%', '/aid/2026/06/28/ae4d1a37233c454da5abe3bc6f66840b.png'));
UPDATE aid_ai_provider SET logo_url='/brand-icons/volcengine_tts.jpg' WHERE provider_code='volcengine_tts' AND (logo_url IS NULL OR logo_url='' OR logo_url='/aid/2026/06/28/be834b781cbd4931b6e4b358c5cc618d.jpg' OR logo_url LIKE CONCAT('%', '/aid/2026/06/28/be834b781cbd4931b6e4b358c5cc618d.jpg'));
UPDATE aid_ai_provider SET logo_url='/brand-icons/minimax.png' WHERE provider_code='minimax' AND (logo_url IS NULL OR logo_url='' OR logo_url='/aid/2026/06/28/c00ff4fa7c5a4540bd3e0ce7757a0dc7.png' OR logo_url LIKE CONCAT('%', '/aid/2026/06/28/c00ff4fa7c5a4540bd3e0ce7757a0dc7.png'));
UPDATE aid_ai_provider SET logo_url='/brand-icons/agnes.png' WHERE provider_code='agnes' AND (logo_url IS NULL OR logo_url='' OR logo_url='/aid/2026/07/06/5960f856dcfa475ca2139e50837898f4.png' OR logo_url LIKE CONCAT('%', '/aid/2026/07/06/5960f856dcfa475ca2139e50837898f4.png'));
UPDATE aid_ai_provider SET logo_url='/brand-icons/vidu.jpg' WHERE provider_code='vidu' AND (logo_url IS NULL OR logo_url='' OR logo_url='/aid/2026/06/28/29a1c29484e04e5393e25cc46a2dff49.jpg' OR logo_url LIKE CONCAT('%', '/aid/2026/06/28/29a1c29484e04e5393e25cc46a2dff49.jpg'));
UPDATE aid_ai_provider SET logo_url='/brand-icons/deepseek.jpg' WHERE provider_code='deepseek' AND (logo_url IS NULL OR logo_url='' OR logo_url='/aid/2026/07/17/33919808cdb2492da44d8889ff305675.jpg' OR logo_url LIKE CONCAT('%', '/aid/2026/07/17/33919808cdb2492da44d8889ff305675.jpg'));
UPDATE aid_ai_provider SET logo_url='/brand-icons/kling.png' WHERE provider_code='kling' AND (logo_url IS NULL OR logo_url='' OR logo_url='/aid/2026/08/10/f78e0d4c85a644a995c7fca0cc5717fc.png' OR logo_url LIKE CONCAT('%', '/aid/2026/08/10/f78e0d4c85a644a995c7fca0cc5717fc.png'));
UPDATE aid_ai_provider SET logo_url='/brand-icons/tokendance.png' WHERE provider_code='tokendance' AND (logo_url IS NULL OR logo_url='' OR logo_url='/aid/2026/09/09/25fd45b0a0f34d14bca576391230467f.png' OR logo_url LIKE CONCAT('%', '/aid/2026/09/09/25fd45b0a0f34d14bca576391230467f.png'));
UPDATE aid_ai_provider SET logo_url='/brand-icons/topaz.ico' WHERE provider_code='topaz' AND (logo_url IS NULL OR logo_url='' OR logo_url='https://account.topazlabs.com/favicon.ico');
UPDATE aid_config SET config_value='/default-avatars/1.png,/default-avatars/2.png,/default-avatars/3.png,/default-avatars/4.png,/default-avatars/5.png' WHERE category='default_avatar' AND config_name='urls' AND (config_value='/aid/2026/06/28/8d5e6414399e4fd3a66b44342ee6d421.png,/aid/2026/06/28/42de0f9179f54330bd5e2ce005c061cd.png,/aid/2026/06/28/db7f8914abbc43a68166a85ba42944f6.png,/aid/2026/06/28/16dea209bde84cd6a61ee23b8155b02a.png,/aid/2026/07/21/194827b2b6a64490b9af8604cd15a582.png' OR config_value LIKE '%/aid/2026/06/28/8d5e6414399e4fd3a66b44342ee6d421.png,%/aid/2026/06/28/42de0f9179f54330bd5e2ce005c061cd.png,%/aid/2026/06/28/db7f8914abbc43a68166a85ba42944f6.png,%/aid/2026/06/28/16dea209bde84cd6a61ee23b8155b02a.png,%/aid/2026/07/21/194827b2b6a64490b9af8604cd15a582.png');
UPDATE aid_config SET config_value='/captcha-backgrounds/1.png,/captcha-backgrounds/2.png,/captcha-backgrounds/3.png,/captcha-backgrounds/4.png' WHERE category='captcha' AND config_name='background_urls' AND (config_value='/aid/2026/06/28/a9c3e9bf02ec4689a2ae776d15c1db16.png,/aid/2026/06/28/5320f5ec7c7847c3acd1445935060ccd.png,/aid/2026/06/28/75b8ef692fac4c2bb92968c60cf44541.png,/aid/2026/06/28/4834114240464a4495cb843955b72f78.png' OR config_value LIKE '%/aid/2026/06/28/a9c3e9bf02ec4689a2ae776d15c1db16.png,%/aid/2026/06/28/5320f5ec7c7847c3acd1445935060ccd.png,%/aid/2026/06/28/75b8ef692fac4c2bb92968c60cf44541.png,%/aid/2026/06/28/4834114240464a4495cb843955b72f78.png');

-- Model-specific icons that reused a shipped supplier image follow the same local asset.
UPDATE aid_ai_model SET logo_url='/brand-icons/dashscope.jpg' WHERE logo_url='/aid/2026/07/06/a1c2f4b38230472cb5074382afa97dcc.jpg' OR logo_url LIKE CONCAT('%', '/aid/2026/07/06/a1c2f4b38230472cb5074382afa97dcc.jpg');
UPDATE aid_ai_model SET logo_url='/brand-icons/volcengine.jpg' WHERE logo_url='/aid/2026/07/06/b0538b7a72444c1ea45cc178d6dd3da1.jpg' OR logo_url LIKE CONCAT('%', '/aid/2026/07/06/b0538b7a72444c1ea45cc178d6dd3da1.jpg');
UPDATE aid_ai_model SET logo_url='/brand-icons/jimeng.jpg' WHERE logo_url='/aid/2026/07/06/20147fcb2e7348c795fe6c14cf079280.jpg' OR logo_url LIKE CONCAT('%', '/aid/2026/07/06/20147fcb2e7348c795fe6c14cf079280.jpg');
UPDATE aid_ai_model SET logo_url='/brand-icons/gemini.png' WHERE logo_url='/aid/2026/06/28/780b866cf29b41269d455f6bba017ab1.png' OR logo_url LIKE CONCAT('%', '/aid/2026/06/28/780b866cf29b41269d455f6bba017ab1.png');
UPDATE aid_ai_model SET logo_url='/brand-icons/openai.png' WHERE logo_url='/aid/2026/06/28/ae4d1a37233c454da5abe3bc6f66840b.png' OR logo_url LIKE CONCAT('%', '/aid/2026/06/28/ae4d1a37233c454da5abe3bc6f66840b.png');
UPDATE aid_ai_model SET logo_url='/brand-icons/volcengine_tts.jpg' WHERE logo_url='/aid/2026/06/28/be834b781cbd4931b6e4b358c5cc618d.jpg' OR logo_url LIKE CONCAT('%', '/aid/2026/06/28/be834b781cbd4931b6e4b358c5cc618d.jpg');
UPDATE aid_ai_model SET logo_url='/brand-icons/minimax.png' WHERE logo_url='/aid/2026/06/28/c00ff4fa7c5a4540bd3e0ce7757a0dc7.png' OR logo_url LIKE CONCAT('%', '/aid/2026/06/28/c00ff4fa7c5a4540bd3e0ce7757a0dc7.png');
UPDATE aid_ai_model SET logo_url='/brand-icons/agnes.png' WHERE logo_url='/aid/2026/07/06/5960f856dcfa475ca2139e50837898f4.png' OR logo_url LIKE CONCAT('%', '/aid/2026/07/06/5960f856dcfa475ca2139e50837898f4.png');
UPDATE aid_ai_model SET logo_url='/brand-icons/vidu.jpg' WHERE logo_url='/aid/2026/06/28/29a1c29484e04e5393e25cc46a2dff49.jpg' OR logo_url LIKE CONCAT('%', '/aid/2026/06/28/29a1c29484e04e5393e25cc46a2dff49.jpg');
UPDATE aid_ai_model SET logo_url='/brand-icons/deepseek.jpg' WHERE logo_url='/aid/2026/07/17/33919808cdb2492da44d8889ff305675.jpg' OR logo_url LIKE CONCAT('%', '/aid/2026/07/17/33919808cdb2492da44d8889ff305675.jpg');
UPDATE aid_ai_model SET logo_url='/brand-icons/kling.png' WHERE logo_url='/aid/2026/08/10/f78e0d4c85a644a995c7fca0cc5717fc.png' OR logo_url LIKE CONCAT('%', '/aid/2026/08/10/f78e0d4c85a644a995c7fca0cc5717fc.png');
UPDATE aid_ai_model SET logo_url='/brand-icons/tokendance.png' WHERE logo_url='/aid/2026/09/09/25fd45b0a0f34d14bca576391230467f.png' OR logo_url LIKE CONCAT('%', '/aid/2026/09/09/25fd45b0a0f34d14bca576391230467f.png');

-- 供应商展示分类独立于协议和调度，仅维护三方聚合与官方厂商。
SET @category_ddl = IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='aid_ai_provider' AND COLUMN_NAME='provider_category'), 'SELECT 1', 'ALTER TABLE aid_ai_provider ADD COLUMN provider_category VARCHAR(16) NULL DEFAULT NULL COMMENT ''展示分类：AGGREGATOR三方聚合，OFFICIAL官方厂商''');
PREPARE category_stmt FROM @category_ddl;
EXECUTE category_stmt;
DEALLOCATE PREPARE category_stmt;
SET @order_ddl = IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='aid_ai_provider' AND COLUMN_NAME='display_order'), 'SELECT 1', 'ALTER TABLE aid_ai_provider ADD COLUMN display_order INT NOT NULL DEFAULT 100 COMMENT ''同类展示顺序，不参与调度''');
PREPARE order_stmt FROM @order_ddl;
EXECUTE order_stmt;
DEALLOCATE PREPARE order_stmt;
-- 只初始化尚未分类的记录，重复执行保留管理员已保存的分类和排序。
UPDATE aid_ai_provider
SET provider_category=CASE WHEN provider_code IN ('dashscope','volcengine','jimeng','gemini','openai','volcengine_tts','minimax','vidu','deepseek','kling','topaz','anthropic','claude','xai','moonshot','zhipu','mistral') THEN 'OFFICIAL' ELSE 'AGGREGATOR' END,
    display_order=CASE provider_code WHEN 'tokendance' THEN 10 WHEN 'newapi' THEN 30 ELSE 100 END
WHERE provider_category IS NULL;

-- New API 站点账户授权与调用凭证相互独立。
SET @newapi_ddl = IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='aid_ai_provider' AND COLUMN_NAME='integration_type'), 'SELECT 1', 'ALTER TABLE `aid_ai_provider` ADD COLUMN `integration_type` VARCHAR(16) NOT NULL DEFAULT ''NATIVE'' COMMENT ''接入方式''');
PREPARE newapi_stmt FROM @newapi_ddl;
EXECUTE newapi_stmt;
DEALLOCATE PREPARE newapi_stmt;
SET @newapi_ddl = IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='aid_ai_provider' AND COLUMN_NAME='new_api_system_token_enabled'), 'SELECT 1', 'ALTER TABLE `aid_ai_provider` ADD COLUMN `new_api_system_token_enabled` TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''启用普通用户访问令牌''');
PREPARE newapi_stmt FROM @newapi_ddl;
EXECUTE newapi_stmt;
DEALLOCATE PREPARE newapi_stmt;
SET @newapi_ddl = IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='aid_ai_provider' AND COLUMN_NAME='new_api_access_token'), 'SELECT 1', 'ALTER TABLE `aid_ai_provider` ADD COLUMN `new_api_access_token` VARCHAR(4096) NULL COMMENT ''上游普通用户访问令牌''');
PREPARE newapi_stmt FROM @newapi_ddl;
EXECUTE newapi_stmt;
DEALLOCATE PREPARE newapi_stmt;
SET @newapi_ddl = IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='aid_ai_provider' AND COLUMN_NAME='new_api_user_id'), 'SELECT 1', 'ALTER TABLE `aid_ai_provider` ADD COLUMN `new_api_user_id` BIGINT NULL COMMENT ''上游普通用户编号''');
PREPARE newapi_stmt FROM @newapi_ddl;
EXECUTE newapi_stmt;
DEALLOCATE PREPARE newapi_stmt;
SET @newapi_ddl = IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='aid_ai_provider' AND COLUMN_NAME='new_api_group'), 'SELECT 1', 'ALTER TABLE `aid_ai_provider` ADD COLUMN `new_api_group` VARCHAR(128) NULL COMMENT ''上游分组''');
PREPARE newapi_stmt FROM @newapi_ddl;
EXECUTE newapi_stmt;
DEALLOCATE PREPARE newapi_stmt;
SET @newapi_ddl = IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='aid_ai_provider' AND COLUMN_NAME='new_api_token_id'), 'SELECT 1', 'ALTER TABLE `aid_ai_provider` ADD COLUMN `new_api_token_id` BIGINT NULL COMMENT ''上游调用令牌编号''');
PREPARE newapi_stmt FROM @newapi_ddl;
EXECUTE newapi_stmt;
DEALLOCATE PREPARE newapi_stmt;

-- Restore the verified MiniMax H3 scenes on previously imported TokenDance catalog models.
-- Keep administrator-defined sceneRules untouched; repeat execution is a no-op.
UPDATE aid_ai_model m
JOIN aid_ai_provider p ON p.id=m.provider_id AND p.provider_code='tokendance'
SET m.capability_json=JSON_SET(m.capability_json, '$.sceneRules', JSON_OBJECT(
      'textToVideo', JSON_OBJECT('requiredInputs',JSON_ARRAY('text'),'allowedInputs',JSON_ARRAY('text')),
      'imageToVideo', JSON_OBJECT('requiredInputs',JSON_ARRAY('firstFrame'),
          'allowedInputs',JSON_ARRAY('text','firstFrame'),'aspectRatioFollowInput',TRUE),
      'startEndToVideo', JSON_OBJECT('requiredInputs',JSON_ARRAY('firstFrame','lastFrame'),
          'allowedInputs',JSON_ARRAY('text','firstFrame','lastFrame'),'aspectRatioFollowInput',TRUE),
      'referenceToVideo', JSON_OBJECT('requiredInputs',JSON_ARRAY('text'),
          'requiredAnyOf',JSON_ARRAY('image','video'),
          'allowedInputs',JSON_ARRAY('text','image','video','audio')))),
    m.supports_first_frame=1, m.supports_last_frame=1, m.supports_multi_image_input=1,
    m.config_version=COALESCE(m.config_version,0)+1,
    m.update_time=NOW(), m.update_by='capability-repair'
WHERE m.real_model_code='minimax-h3'
  AND m.protocol='tokendance:minimax:video_generation_v2'
  AND m.del_flag='0' AND JSON_VALID(m.capability_json)
  AND JSON_CONTAINS_PATH(m.capability_json,'one','$.sceneRules')=0;
