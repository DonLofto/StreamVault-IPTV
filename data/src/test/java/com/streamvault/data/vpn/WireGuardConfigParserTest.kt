package com.streamvault.data.vpn

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Result
import com.streamvault.domain.vpn.VpnProtocol
import org.junit.Test

class WireGuardConfigParserTest {

    @Test
    fun `parse valid WireGuard configuration succeeds`() {
        val sampleConf = """
            # Sample WireGuard Config
            [Interface]
            PrivateKey = YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE=
            Address = 10.2.0.2/32, fd00::2/128
            DNS = 1.1.1.1, 8.8.8.8
            MTU = 1380

            [Peer]
            PublicKey = YmJmYmJmYmJmYmJmYmJmYmJmYmJmYmJmYmJmYmJmYmI=
            PresharedKey = Y2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2M=
            Endpoint = vpn.example.com:51820
            AllowedIPs = 0.0.0.0/0, ::/0
        """.trimIndent()

        val result = WireGuardConfigParser.parse(sampleConf, "ProtonVPN")
        assertThat(result).isInstanceOf(Result.Success::class.java)

        val config = (result as Result.Success).data
        assertThat(config.name).isEqualTo("ProtonVPN")
        assertThat(config.protocol).isEqualTo(VpnProtocol.WIREGUARD)
        assertThat(config.privateKey).isEqualTo("YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE=")
        assertThat(config.publicKey).isEqualTo("YmJmYmJmYmJmYmJmYmJmYmJmYmJmYmJmYmJmYmJmYmI=")
        assertThat(config.presharedKey).isEqualTo("Y2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2M=")
        assertThat(config.endpoint).isEqualTo("vpn.example.com")
        assertThat(config.port).isEqualTo(51820)
        assertThat(config.address).isEqualTo("10.2.0.2/32, fd00::2/128")
        assertThat(config.dns).isEqualTo("1.1.1.1, 8.8.8.8")
        assertThat(config.mtu).isEqualTo(1380)
    }

    @Test
    fun `parse invalid config returns error when PrivateKey is missing`() {
        val invalidConf = """
            [Interface]
            Address = 10.0.0.2/32

            [Peer]
            PublicKey = YmJmYmJmYmJmYmJmYmJmYmJmYmJmYmJmYmJmYmJmYmI=
            Endpoint = 192.0.2.1:51820
        """.trimIndent()

        val result = WireGuardConfigParser.parse(invalidConf)
        assertThat(result).isInstanceOf(Result.Error::class.java)
        assertThat((result as Result.Error).message).contains("Missing PrivateKey")
    }
}
