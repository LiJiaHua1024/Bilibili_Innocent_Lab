package com.bilibili.app.gemini.base.player

enum class SponsorBusiness { UGC, PGC }
open class SponsorParent(val bizType: SponsorBusiness)
class GeminiCommonPlayableParams(val bvId: String, val cid: Long, val avid: Long,
    business: SponsorBusiness = SponsorBusiness.UGC) : SponsorParent(business)
