package com.weavetext.ime.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubRepositoryTest {
    @Test fun completeRepositoryAddressesNormalizeToTheSameRepository() {
        val expected = GitHubRepository("tester", "plugins")
        for (address in listOf(
            "tester/plugins",
            "https://github.com/tester/plugins",
            "  https://github.com/tester/plugins.git/  ",
            "https://github.com/tester/plugins.GIT?tab=readme-ov-file#readme",
            "https://www.github.com/tester/plugins/",
            "HTTPS://GITHUB.COM:443/tester/plugins",
            "github.com/tester/plugins",
            "www.github.com/tester/plugins",
        )) assertEquals(address, expected, GitHubRepository.parse(address))
    }

    @Test fun directoryAddressInfersTheBranchAndDecodesTheDirectory() {
        assertEquals(GitHubRepository("tester", "plugins", "main", "voice/中文 插件"),
            GitHubRepository.parse("https://github.com/tester/plugins/tree/main/voice/%E4%B8%AD%E6%96%87%20%E6%8F%92%E4%BB%B6?tab=readme#overview"))
        assertEquals(GitHubRepository("tester", "plugins", "v1.2.0"),
            GitHubRepository.parse("https://github.com/tester/plugins/tree/v1.2.0"))
    }

    @Test fun encodedSlashInTheBranchIsKeptTogether() {
        assertEquals(GitHubRepository("tester", "plugins", "feature/custom", "asr"),
            GitHubRepository.parse("https://github.com/tester/plugins/tree/feature%2Fcustom/asr"))
    }

    @Test fun aLiteralPlusIsNotChangedIntoASpace() {
        assertEquals("voice+tools", GitHubRepository.parse("https://github.com/tester/plugins/tree/main/voice+tools").path)
        assertEquals("voice+tools", GitHubRepository.parse("https://github.com/tester/plugins/tree/main/voice%2Btools").path)
        assertEquals("feature+asr", GitHubRepository.parse("https://github.com/tester/plugins/tree/feature+asr/voice").ref)
    }

    @Test fun manifestAddressUsesItsDirectorySoTheEntrypointIsIncluded() {
        assertEquals(GitHubRepository("tester", "plugins", "main", "voice/asr"),
            GitHubRepository.parse("https://github.com/tester/plugins/blob/main/voice/asr/manifest.yaml#L1"))
        assertEquals(GitHubRepository("tester", "plugins", "main"),
            GitHubRepository.parse("https://github.com/tester/plugins/blob/main/manifest.yaml"))
    }

    @Test fun packageAddressKeepsTheCompleteFilePath() {
        assertEquals(GitHubRepository("tester", "plugins", "main", "voice/plugin+one.xipk"),
            GitHubRepository.parse("https://github.com/tester/plugins/blob/main/voice/plugin+one.xipk?raw=true"))
    }

    @Test fun fileLinksDoNotRequireAParticularExtension() {
        for (file in listOf("plugin.zip", "plugin.custom", "plugin", "README.md")) {
            assertEquals("voice/$file", GitHubRepository.parse("https://github.com/tester/plugins/blob/main/voice/$file").path)
        }
    }

    @Test fun manualFieldsOverrideTheLinkAndSupportSlashBranches() {
        assertEquals(GitHubRepository("tester", "plugins", "release/voice", "chosen"),
            GitHubRepository.parse("https://github.com/tester/plugins/tree/main/voice", "release/voice", "chosen"))
        assertEquals(GitHubRepository("tester", "plugins", "feature/custom", "asr"),
            GitHubRepository.parse("https://github.com/tester/plugins/tree/feature/custom/asr", "feature/custom"))
    }

    @Test fun invalidHostsCredentialsAndTraversalStayRejected() {
        for (address in listOf(
            "https://github.com.evil.example/tester/plugins",
            "https://github.com@evil.example/tester/plugins",
            "https://secret@github.com/tester/plugins",
            "https://github.com:8443/tester/plugins",
            "http://github.com/tester/plugins",
            "https://github.com/tester/plugins/tree/main/../secret",
            "https://github.com/tester/plugins/tree/main/%2e%2e/secret",
            "https://github.com/tester/plugins/tree/main/path%5Csecret",
        )) assertTrue(address, runCatching { GitHubRepository.parse(address) }.isFailure)
    }
}
