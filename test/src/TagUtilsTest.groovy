package com.nitte.merch

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import static org.assertj.core.api.Assertions.*

/**
 * Unit tests for TagUtils.
 * No Jenkins context needed — pure Groovy logic.
 */
class TagUtilsTest {

    // ── getTagInfo: valid release tags ───────────────────────────────────────

    @Test
    void 'v1_2_3 is a valid release tag'() {
        def info = TagUtils.getTagInfo('v1.2.3')
        assertThat(info.valid).isTrue()
        assertThat(info.isRelease).isTrue()
        assertThat(info.major).isEqualTo(1)
        assertThat(info.minor).isEqualTo(2)
        assertThat(info.patch).isEqualTo(3)
        assertThat(info.prerelease).isEmpty()
        assertThat(info.version).isEqualTo('1.2.3')
        assertThat(info.tag).isEqualTo('v1.2.3')
    }

    @Test
    void 'v0_1_0 is a valid release tag'() {
        def info = TagUtils.getTagInfo('v0.1.0')
        assertThat(info.valid).isTrue()
        assertThat(info.isRelease).isTrue()
        assertThat(info.version).isEqualTo('0.1.0')
    }

    @Test
    void 'v10_20_30 handles large numbers'() {
        def info = TagUtils.getTagInfo('v10.20.30')
        assertThat(info.valid).isTrue()
        assertThat(info.major).isEqualTo(10)
        assertThat(info.minor).isEqualTo(20)
        assertThat(info.patch).isEqualTo(30)
    }

    // ── getTagInfo: pre-release tags ─────────────────────────────────────────

    @Test
    void 'v1_2_3-rc_1 is a pre-release tag'() {
        def info = TagUtils.getTagInfo('v1.2.3-rc.1')
        assertThat(info.valid).isTrue()
        assertThat(info.isRelease).isFalse()
        assertThat(info.prerelease).isEqualTo('rc.1')
        assertThat(info.version).isEqualTo('1.2.3')
    }

    @Test
    void 'v2_0_0-alpha is a pre-release tag'() {
        def info = TagUtils.getTagInfo('v2.0.0-alpha')
        assertThat(info.valid).isTrue()
        assertThat(info.isRelease).isFalse()
        assertThat(info.prerelease).isEqualTo('alpha')
    }

    @Test
    void 'v1_0_0-SNAPSHOT is a pre-release tag'() {
        def info = TagUtils.getTagInfo('v1.0.0-SNAPSHOT')
        assertThat(info.valid).isTrue()
        assertThat(info.isRelease).isFalse()
    }

    // ── getTagInfo: invalid inputs ────────────────────────────────────────────

    @ParameterizedTest(name = "'{0}' is invalid")
    @CsvSource([
        'main',
        '1.2.3',      // missing v prefix
        'v1.2',       // missing patch
        'v.1.2.3',    // extra dot
        '',
        'latest',
        'dev-123-abc',
    ])
    void 'invalid tags return valid=false'(String tag) {
        def info = TagUtils.getTagInfo(tag)
        assertThat(info.valid).isFalse()
        assertThat(info.isRelease).isFalse()
        assertThat(info.version).isEmpty()
    }

    @Test
    void 'null input returns safe defaults'() {
        def info = TagUtils.getTagInfo(null)
        assertThat(info.valid).isFalse()
        assertThat(info.tag).isEmpty()
    }
}
