package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.*
import org.junit.Test

class PlayerAiSubtitleFeatureInstallerTest {
    @Test fun onlyValidOnDemandVideosAreEligible() {
        assertTrue(PlayerAiSubtitleFeatureInstaller.eligible("ugc", 1, 2))
        assertTrue(PlayerAiSubtitleFeatureInstaller.eligible("pgc", 1, 2))
        assertFalse(PlayerAiSubtitleFeatureInstaller.eligible("live", 1, 2))
        assertFalse(PlayerAiSubtitleFeatureInstaller.eligible(null, 1, 2))
        assertFalse(PlayerAiSubtitleFeatureInstaller.eligible("ugc", 0, 2))
        assertFalse(PlayerAiSubtitleFeatureInstaller.eligible("ugc", 1, -1))
    }
}
