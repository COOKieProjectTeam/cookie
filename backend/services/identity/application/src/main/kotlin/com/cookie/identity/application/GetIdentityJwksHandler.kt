package com.cookie.identity.application

import com.cookie.identity.application.ports.AccessTokenProvider
import com.cookie.identity.application.ports.GetIdentityJwksUseCase

class GetIdentityJwksHandler(
    private val accessTokens: AccessTokenProvider,
) : GetIdentityJwksUseCase {
    override fun execute(): List<PublicJwk> = accessTokens.publicKeys()
}
