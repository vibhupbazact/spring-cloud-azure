/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See LICENSE in the project root for
 * license information.
 */
package com.microsoft.azure.spring.cloud.config.stores;

import java.net.URI;
import java.time.Duration;

import org.apache.commons.lang3.StringUtils;

import com.azure.core.credential.TokenCredential;
import com.azure.identity.ManagedIdentityCredentialBuilder;
import com.azure.security.keyvault.secrets.SecretAsyncClient;
import com.azure.security.keyvault.secrets.SecretClientBuilder;
import com.azure.security.keyvault.secrets.models.KeyVaultSecret;
import com.microsoft.azure.spring.cloud.config.AzureCloudConfigProperties;
import com.microsoft.azure.spring.cloud.config.KeyVaultCredentialProvider;
import com.microsoft.azure.spring.cloud.context.core.config.AzureManagedIdentityProperties;

public class KeyVaultClient {

    private SecretAsyncClient secretClient;

    /**
     * Builds an Async client to a Key Vaults Secrets
     * 
     * @param uri Key Vault URI
     * @param tokenCredentialProvider user created credentials for authenticating to Key
     * Vault
     * @param properties Azure Configuration Managed Identity credentials
     */
    public KeyVaultClient(URI uri, KeyVaultCredentialProvider tokenCredentialProvider,
            AzureCloudConfigProperties properties) {
        SecretClientBuilder builder = new SecretClientBuilder();
        TokenCredential tokenCredential = null;
        if (tokenCredentialProvider != null) {
            tokenCredential = tokenCredentialProvider.getKeyVaultCredential("https://" + uri.getHost());
        }

        AzureManagedIdentityProperties msiProps = properties.getManagedIdentity();
        if (tokenCredential != null && msiProps != null) {
            throw new IllegalArgumentException("More than 1 Conncetion method was set for connecting to Key Vault.");
        }

        if (tokenCredential != null) {
            // User Provided Token Credential
            builder.credential(tokenCredential);
        } else if (tokenCredential == null && msiProps != null && StringUtils.isNotEmpty(msiProps.getClientId())) {
            // User Assigned Identity - Client ID through configuration file.
            builder.credential(new ManagedIdentityCredentialBuilder().clientId(msiProps.getClientId()).build());
        } else if (tokenCredential == null) {
            // System Assigned Identity.
            builder.credential(new ManagedIdentityCredentialBuilder().build());
        }
        secretClient = builder.vaultUrl("https://" + uri.getHost()).buildAsyncClient();
    }

    /**
     * Gets the specified secret using the Secret Identifier
     * 
     * @param secretIdentifier The Secret Identifier to Secret
     * @param timeout How long it waits for a response from Key Vault
     * @return Secret values that matches the secretIdentifier
     */
    public KeyVaultSecret getSecret(URI secretIdentifier, int timeout) {
        String[] tokens = secretIdentifier.getPath().split("/");

        String name = (tokens.length >= 3 ? tokens[2] : null);
        String version = (tokens.length >= 4 ? tokens[3] : null);
        return secretClient.getSecret(name, version).block(Duration.ofSeconds(timeout));
    }

}
