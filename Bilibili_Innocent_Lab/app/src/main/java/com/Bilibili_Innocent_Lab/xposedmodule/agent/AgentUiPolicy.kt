package com.Bilibili_Innocent_Lab.xposedmodule.agent

/** 模型、截图和页面文案均不能授予敏感操作权限；执行器在每次动作前独立检查。 */
internal object AgentUiPolicy {
    private val sensitive = Regex(
        "密码|口令|支付|付款|购买|充值|提现|转账|打赏|充电|钱包|开通.*会员|续费|订阅|实名|身份证|护照|银行卡|人脸验证|身份认证|账户注销|账号注销|注销账号|注销账户|绑定手机|更换手机|修改手机|手机号|手机号码|绑定邮箱|修改邮箱|更换邮箱|验证码|安全验证|验证身份|账号安全|账户安全|安全中心|密保|解绑|注销登录|退出登录|" +
            "password|passcode|pay(?:ment)?|purchase|checkout|recharge|withdraw|transfer|donat|subscribe|renew|real.?name|identity|credit.?card|bank.?card|verification.?code|delete.?account|change.?phone|change.?email|passport|wallet|\\b(?:OTP|PIN|CVV|CVC|2FA)\\b|one.?time.?code|two.?factor",
        RegexOption.IGNORE_CASE
    )
    private val secretValue = Regex("(?:\\d[ -]?){15,19}|\\d{17}[0-9Xx]|(?<!\\d)1[3-9]\\d{9}(?!\\d)|(?:Cookie|access_key|Authorization|Bearer)\\s*[:= ]", RegexOption.IGNORE_CASE)
    private val amount = Regex("[¥￥$€]\\s*\\d|\\d+(?:\\.\\d{1,2})?\\s*(?:元|人民币|USD|CNY)", RegexOption.IGNORE_CASE)
    private val confirmation = Regex("确认|确定|继续|同意|完成|confirm|continue|agree|done", RegexOption.IGNORE_CASE)
    fun sensitiveControl(label: String): Boolean = sensitive.containsMatchIn(label)
    fun sensitiveConfirmation(label: String, windowText: String): Boolean =
        confirmation.containsMatchIn(label) && amount.containsMatchIn(windowText)
    fun credentialText(text: String): Boolean = secretValue.containsMatchIn(text)
    fun publicText(text: String): String = if (credentialText(text)) "[受保护内容]" else text.take(80)
    fun protectedControl(label: String, descendants: List<String>, password: Boolean): Boolean = password ||
        sensitiveControl(label) || descendants.any(::sensitiveControl)
    fun mayClick(label: String, protected: Boolean): Boolean = label.isNotBlank() && !protected
    fun mayAct(authorized: Boolean, resultDone: Boolean, now: Long, expiresAt: Long): Boolean =
        authorized && !resultDone && now < expiresAt
    fun allowInput(label: String, password: Boolean, value: String): Boolean = !password &&
        !sensitiveControl(label) && value.isNotBlank() && value.length <= 500 &&
        value.none { it.isISOControl() && it != '\n' } && !secretValue.containsMatchIn(value)
    fun snapshotCurrent(expectedWindow: Int, actualWindow: Int, ageMs: Long, sameTarget: Boolean): Boolean =
        expectedWindow == actualWindow && ageMs in 0..60_000 && sameTarget
    fun systemNotification(sdk: Int): Boolean = sdk >= 36
}
