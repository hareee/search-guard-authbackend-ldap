/*
 * Copyright 2016 by floragunn UG (haftungsbeschränkt) - All rights reserved
 * 
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed here is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * 
 * This software is free of charge for non-commercial and academic use. 
 * For commercial use in a production environment you have to obtain a license 
 * from https://floragunn.com
 * 
 */

package com.floragunn.dlic.auth.ldap.backend;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.security.AccessController;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.security.cert.CertificateException;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.ElasticsearchSecurityException;
import org.elasticsearch.SpecialPermission;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.ldaptive.BindRequest;
import org.ldaptive.Connection;
import org.ldaptive.ConnectionConfig;
import org.ldaptive.Credential;
import org.ldaptive.DefaultConnectionFactory;
import org.ldaptive.LdapAttribute;
import org.ldaptive.LdapEntry;
import org.ldaptive.LdapException;
import org.ldaptive.SearchScope;
import org.ldaptive.ssl.AllowAnyHostnameVerifier;
import org.ldaptive.ssl.CredentialConfig;
import org.ldaptive.ssl.CredentialConfigFactory;
import org.ldaptive.ssl.HostnameVerifyingTrustManager;
import org.ldaptive.ssl.SslConfig;

import com.floragunn.dlic.auth.ldap.LdapUser;
import com.floragunn.dlic.auth.ldap.util.ConfigConstants;
import com.floragunn.dlic.auth.ldap.util.LdapHelper;
import com.floragunn.dlic.auth.ldap.util.Utils;
import com.floragunn.searchguard.auth.AuthorizationBackend;
import com.floragunn.searchguard.ssl.util.SSLConfigConstants;
import com.floragunn.searchguard.support.WildcardMatcher;
import com.floragunn.searchguard.user.AuthCredentials;
import com.floragunn.searchguard.user.User;

public class LDAPAuthorizationBackend implements AuthorizationBackend {

    private static final String[] EMPTY_STRING_ARRAY = new String[0];
    static final String JKS = "JKS";
    static final String PKCS12 = "PKCS12";
    static final String DEFAULT_KEYSTORE_PASSWORD = "changeit";
    static final String ONE_PLACEHOLDER = "{1}";
    static final String TWO_PLACEHOLDER = "{2}";
    static final String DEFAULT_ROLEBASE = "";
    static final String DEFAULT_ROLESEARCH = "(member={0})";
    static final String DEFAULT_ROLENAME = "name";
    static final String DEFAULT_USERROLENAME = "memberOf";

    static {
        Utils.printLicenseInfo();
    }

    protected static final Logger log = LogManager.getLogger(LDAPAuthorizationBackend.class);
    final Settings settings;

    public LDAPAuthorizationBackend(final Settings settings) {
        this.settings = settings;
    }
    
    public static Connection getConnection(final Settings settings) throws Exception {
        
        final SecurityManager sm = System.getSecurityManager();

        if (sm != null) {
            sm.checkPermission(new SpecialPermission());
        }
        
        try {
            return AccessController.doPrivileged(new PrivilegedExceptionAction<Connection>() {
                @Override
                public Connection run() throws Exception {
                    return getConnection0(settings);
                }
            });
        } catch (PrivilegedActionException e) {
            throw e.getException();
        }

    }

    private static Connection getConnection0(final Settings settings) throws KeyStoreException, NoSuchAlgorithmException,
    CertificateException, FileNotFoundException, IOException, LdapException {
        final boolean enableSSL = settings.getAsBoolean(ConfigConstants.LDAPS_ENABLE_SSL, false);

        final String[] ldapHosts = settings.getAsArray(ConfigConstants.LDAP_HOSTS, new String[] { "localhost" });

        Connection connection = null;

        for (int i = 0; i < ldapHosts.length; i++) {
            
            if(log.isTraceEnabled()) {
                log.trace("Connect to {}", ldapHosts[i]);
            }

            try {

                final String[] split = ldapHosts[i].split(":");

                int port = 389;

                if (split.length > 1) {
                    port = Integer.parseInt(split[1]);
                } else {
                    port = enableSSL ? 636 : 389;
                }

                final ConnectionConfig config = new ConnectionConfig();
                config.setLdapUrl("ldap" + (enableSSL ? "s" : "") + "://" + split[0] + ":" + port);
                
                if(log.isTraceEnabled()) {
                    log.trace("Connect to {}", config.getLdapUrl());
                }
                
                Map<String, Object> props = configureSSL(config, settings);

                DefaultConnectionFactory connFactory = new DefaultConnectionFactory(config);
                connFactory.getProvider().getProviderConfig().setProperties(props);
                connection = connFactory.getConnection();
                
                final String bindDn = settings.get(ConfigConstants.LDAP_BIND_DN, null);
                final String password = settings.get(ConfigConstants.LDAP_PASSWORD, null);

                if (log.isDebugEnabled()) {
                    log.debug("bindDn {}, password {}", bindDn, password != null && password.length() > 0?"****":"<not set>");
                }
                
                if (bindDn != null && (password == null || password.length() == 0)) {
                    log.error("No password given for bind_dn {}. Will try to authenticate anonymously to ldap", bindDn);
                }
                
                BindRequest br = new BindRequest();
                
                if (bindDn != null && password != null && password.length() > 0) {
                    br = new BindRequest(bindDn, new Credential(password));
                }
                
                connection.open(br);

                if (connection != null && connection.isOpen()) {
                    break;
                }
            } catch (final Exception e) {
                log.warn("Unable to connect to ldapserver {} due to {}. Try next.", ldapHosts[i], e.toString());
                if(log.isDebugEnabled()) {
                    log.debug("Unable to connect to ldapserver due to ",e);
                }
                Utils.unbindAndCloseSilently(connection);
                continue;
            }
        }

        if (connection == null || !connection.isOpen()) {
            throw new LdapException("Unable to connect to any of those ldap servers " + Arrays.toString(ldapHosts));
        }

        return connection;
    }

    private static Map<String, Object> configureSSL(final ConnectionConfig config, final Settings settings) throws KeyStoreException,
            NoSuchAlgorithmException, CertificateException, FileNotFoundException, IOException {
        Map<String, Object> props = new HashMap<String, Object>();
        final boolean enableSSL = settings.getAsBoolean(ConfigConstants.LDAPS_ENABLE_SSL, false);
        final boolean enableStartTLS = settings.getAsBoolean(ConfigConstants.LDAPS_ENABLE_START_TLS, false);

        if (enableSSL || enableStartTLS) {
            
            final boolean enableClientAuth = settings.getAsBoolean(ConfigConstants.LDAPS_ENABLE_SSL_CLIENT_AUTH, false);
            final boolean verifyHostnames = settings.getAsBoolean(ConfigConstants.LDAPS_VERIFY_HOSTNAMES, true);
            
            final SslConfig sslConfig = new SslConfig();
            
            Environment env = new Environment(settings);
     
            File trustStore = env.configFile().resolve(settings.get(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_TRUSTSTORE_FILEPATH)).toFile();
            String truststorePassword = settings.get(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_TRUSTSTORE_PASSWORD, DEFAULT_KEYSTORE_PASSWORD);
            
            File keystore = null;
            String keystorePassword = settings.get(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_KEYSTORE_PASSWORD, DEFAULT_KEYSTORE_PASSWORD);        
        
            final String _keystore = settings.get(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_KEYSTORE_FILEPATH);
            
            if(_keystore != null) {
                keystore = env.configFile().resolve(settings.get(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_KEYSTORE_FILEPATH)).toFile();
            }
            
            if (trustStore != null) {
                final KeyStore myTrustStore = KeyStore.getInstance(trustStore.getName().endsWith(JKS.toLowerCase()) ? JKS : PKCS12);
                myTrustStore.load(new FileInputStream(trustStore),
                        truststorePassword == null || truststorePassword.isEmpty() ? null : truststorePassword.toCharArray());
                
                if (enableClientAuth && keystore != null) {
                    final KeyStore keyStore = KeyStore.getInstance(keystore.getName().endsWith(JKS.toLowerCase()) ? JKS : PKCS12);
                    keyStore.load(new FileInputStream(keystore), keystorePassword == null || keystorePassword.isEmpty() ? null
                            : keystorePassword.toCharArray());
                    
                    CredentialConfig cc = CredentialConfigFactory.createKeyStoreCredentialConfig(myTrustStore, keyStore, keystorePassword);
                    sslConfig.setCredentialConfig(cc);
                } else {
                    CredentialConfig cc = CredentialConfigFactory.createKeyStoreCredentialConfig(myTrustStore);
                    sslConfig.setCredentialConfig(cc);
                }
                
            }

            if(enableStartTLS && !verifyHostnames) {
                props.put("jndi.starttls.allowAnyHostname", "true");
            }
            
            if(!verifyHostnames) {
                sslConfig.setTrustManagers(new HostnameVerifyingTrustManager(new AllowAnyHostnameVerifier(), "dummy"));
            }

            //https://github.com/floragunncom/search-guard/issues/227
            final String[] enabledCipherSuites = settings.getAsArray(ConfigConstants.LDAPS_ENABLED_SSL_CIPHERS, EMPTY_STRING_ARRAY);   
            final String[] enabledProtocols = settings.getAsArray(ConfigConstants.LDAPS_ENABLED_SSL_PROTOCOLS, new String[] { "TLSv1.1", "TLSv1.2" });   
            

            if(enabledCipherSuites.length > 0) {
                sslConfig.setEnabledCipherSuites(enabledCipherSuites);
                log.debug("enabled ssl cipher suites for ldaps {}", Arrays.toString(enabledCipherSuites));
            }
            
            log.debug("enabled ssl/tls protocols for ldaps {}", Arrays.toString(enabledProtocols));
            sslConfig.setEnabledProtocols(enabledProtocols);
            config.setSslConfig(sslConfig);
        }

        config.setUseSSL(enableSSL);
        config.setUseStartTLS(enableStartTLS);
        config.setConnectTimeout(5000L); // 5 sec
        return props;
        
    }

    @Override
    public void fillRoles(final User user, final AuthCredentials optionalAuthCreds) throws ElasticsearchSecurityException {

        if(user == null) {
            return;
        }
                
        String authenticatedUser;
        String originalUserName;
        LdapEntry entry = null;
        String dn = null;
        
        if(user instanceof LdapUser) {
            entry = ((LdapUser) user).getUserEntry();
            authenticatedUser = entry.getDn(); 
            originalUserName = ((LdapUser) user).getOriginalUsername();
        } else {
            authenticatedUser =  Utils.escapeStringRfc2254(user.getName());
            originalUserName = user.getName();
        }
        
        final boolean rolesearchEnabled = settings.getAsBoolean(ConfigConstants.LDAP_AUTHZ_ROLESEARCH_ENABLED, true);

        
        if(log.isTraceEnabled()) {
            log.trace("user class: {}", user.getClass());
            log.trace("authenticatedUser: {}", authenticatedUser);
            log.trace("originalUserName: {}", originalUserName);
            log.trace("entry: {}", String.valueOf(entry));
            log.trace("dn: {}", dn);
        }

        final String[] skipUsers = settings.getAsArray(ConfigConstants.LDAP_AUTHZ_SKIP_USERS, EMPTY_STRING_ARRAY);
        if (skipUsers.length > 0 && WildcardMatcher.matchAny(skipUsers, authenticatedUser)) {
            if (log.isDebugEnabled()) {
                log.debug("Skipped search roles of user {}", authenticatedUser);
            }
            return;
        }
       
        Connection connection = null;

        try {

            if(entry == null || dn == null) {
            
                connection = getConnection(settings);
                
                if (isValidDn(authenticatedUser)) {
                    // assume dn
                    if(log.isTraceEnabled()) {
                        log.trace("{} is a valid DN", authenticatedUser);
                    }
                    
                    entry = LdapHelper.lookup(connection, authenticatedUser);
    
                    if (entry == null) {
                        throw new ElasticsearchSecurityException("No user '" + authenticatedUser + "' found");
                    }
    
                } else {
                    entry = LDAPAuthenticationBackend.exists(user.getName(), connection, settings);
                    
                    if(log.isTraceEnabled()) {
                        log.trace("{} is not a valid DN and was resolved to {}", authenticatedUser, entry);
                    }
                    
                    if (entry == null || entry.getDn() == null) {
                        throw new ElasticsearchSecurityException("No user " + authenticatedUser + " found");
                    }
                }
    
                dn = entry.getDn();
    
                if(log.isTraceEnabled()) {
                    log.trace("User found with DN {}", dn);
                }
            }

            final Set<LdapName> roles = new HashSet<LdapName>(150);

            // Roles as an attribute of the user entry
            // default is userrolename: memberOf
            final String userRoleName = settings
                    .get(ConfigConstants.LDAP_AUTHZ_USERROLENAME, DEFAULT_USERROLENAME);
            
            if(log.isTraceEnabled()) {
                log.trace("userRoleName: {}", userRoleName);
            }
            
            if (entry.getAttribute(userRoleName) != null) {
                final Collection<String> userRoles = entry.getAttribute(userRoleName).getStringValues();

                for (final String possibleRoleDN : userRoles) {
                    if (isValidDn(possibleRoleDN)) {
                        roles.add(new LdapName(possibleRoleDN));
                    } else {
                        if(log.isDebugEnabled()) {
                            log.debug("Cannot add {} as a role because its not a valid dn", possibleRoleDN);
                        }
                    }
                }
            }
            
            if(log.isTraceEnabled()) {
                log.trace("User attr. roles count: {}", roles.size());
                log.trace("User attr. roles {}", roles);
            }

            // The attribute in a role entry containing the name of that role, Default is "name".
            // Can also be "dn" to use the full DN as rolename.
            // rolename: name
            final String roleName = settings.get(ConfigConstants.LDAP_AUTHZ_ROLENAME, DEFAULT_ROLENAME);
            
            if(log.isTraceEnabled()) {
                log.trace("roleName: {}", roleName);
            }

            // Specify the name of the attribute which value should be substituted with {2}
            // Substituted with an attribute value from user's directory entry, of the authenticated user
            // userroleattribute: null
            final String userRoleAttributeName = settings.get(ConfigConstants.LDAP_AUTHZ_USERROLEATTRIBUTE, null);
            
            if(log.isTraceEnabled()) {
                log.trace("userRoleAttribute: {}", userRoleAttributeName);
                log.trace("rolesearch: {}", settings.get(ConfigConstants.LDAP_AUTHZ_ROLESEARCH, DEFAULT_ROLESEARCH));
            }
            
            String userRoleAttributeValue = null;
            final LdapAttribute userRoleAttribute = entry.getAttribute(userRoleAttributeName);

            if (userRoleAttribute != null) {
                userRoleAttributeValue = userRoleAttribute.getStringValue();
            }

            final List<LdapEntry> rolesResult = !rolesearchEnabled?null:LdapHelper.search(
                    connection,
                    settings.get(ConfigConstants.LDAP_AUTHZ_ROLEBASE, DEFAULT_ROLEBASE),
                    settings.get(ConfigConstants.LDAP_AUTHZ_ROLESEARCH, DEFAULT_ROLESEARCH)
                    .replace(LDAPAuthenticationBackend.ZERO_PLACEHOLDER, dn).replace(ONE_PLACEHOLDER, originalUserName)
                    .replace(TWO_PLACEHOLDER, userRoleAttributeValue == null ? TWO_PLACEHOLDER : userRoleAttributeValue), SearchScope.SUBTREE);
            
            if(rolesResult != null && !rolesResult.isEmpty()) {
                for (final Iterator<LdapEntry> iterator = rolesResult.iterator(); iterator.hasNext();) {
                    final LdapEntry searchResultEntry = iterator.next();
                    roles.add(new LdapName(searchResultEntry.getDn()));
                }
            }

            if(log.isTraceEnabled()) {
                log.trace("non user attr. roles count: {}", rolesResult != null?rolesResult.size():0);
                log.trace("non user attr. roles {}", rolesResult);
                log.trace("roles count total {}", roles.size());
            }

            // nested roles
            if (settings.getAsBoolean(ConfigConstants.LDAP_AUTHZ_RESOLVE_NESTED_ROLES, false)) {

                if(log.isTraceEnabled()) {
                    log.trace("Evaluate nested roles");
                }

                final Set<LdapName> nestedReturn = new HashSet<LdapName>(roles);

                for (final LdapName roleLdapName: roles) {
                    final Set<LdapName> nestedRoles = resolveNestedRoles(roleLdapName, connection, userRoleName, 0, rolesearchEnabled);

                    if(log.isTraceEnabled()) {
                        log.trace("{} nested roles for {}", nestedRoles.size(), roleLdapName);
                    }

                    nestedReturn.addAll(nestedRoles);
                }

                for (final LdapName roleLdapName: nestedReturn) {
                    final String role = getRoleFromAttribute(roleLdapName, roleName);
                    
                    if(!Strings.isNullOrEmpty(role)) {
                        user.addRole(role);
                    } else {
                        log.warn("No or empty attribute '{}' for entry {}", roleName, roleLdapName);
                    }
                }

                /*
                if (user instanceof LdapUser) {
                    ((LdapUser) user).addRoleEntries(nestedReturn);
                }*/

            } else {

                for (final LdapName roleLdapName: roles) {
                    final String role = getRoleFromAttribute(roleLdapName, roleName);
                    
                    if(!Strings.isNullOrEmpty(role)) {
                        user.addRole(role);
                    } else {
                        log.warn("No or empty attribute '{}' for entry {}", roleName, roleLdapName);
                    }
                }

                /*if (user instanceof LdapUser) {
                    ((LdapUser) user).addRoleEntries(roles.values());
                }*/
            }
            

            if(log.isTraceEnabled()) {
                log.trace("returned user: {}", user);
            }

        } catch (final Exception e) {
            if(log.isDebugEnabled()) {
                log.debug("Unable to fill user roles due to ",e);
            }
            throw new ElasticsearchSecurityException(e.toString(), e);
        } finally {
            Utils.unbindAndCloseSilently(connection);
        }

    }

    protected Set<LdapName> resolveNestedRoles(final LdapName roleDn, final Connection ldapConnection, String userRoleName, int depth, final boolean rolesearchEnabled)
            throws ElasticsearchSecurityException, LdapException {
        depth++;

        final Set<LdapName> result = new HashSet<LdapName>(20);

        final LdapEntry e0 = LdapHelper.lookup(ldapConnection, roleDn.toString());

        if (e0.getAttribute(userRoleName) != null) {
            final Collection<String> userRoles = e0.getAttribute(userRoleName).getStringValues();

            for (final String possibleRoleDN : userRoles) {
                if (isValidDn(possibleRoleDN)) {
                    try {
                        result.add(new LdapName(possibleRoleDN));
                    } catch (InvalidNameException e) {
                        // ignore
                    }
                } else {
                    if (log.isDebugEnabled()) {
                        log.debug("Cannot add {} as a role because its not a valid dn", possibleRoleDN);
                    }
                }
            }
        }

        if (log.isTraceEnabled()) {
            log.trace("result nested attr count for depth {} : {}", depth, result.size());
        }

        final List<LdapEntry> rolesResult = !rolesearchEnabled?null:LdapHelper
                .search(ldapConnection,
                        settings.get(ConfigConstants.LDAP_AUTHZ_ROLEBASE, DEFAULT_ROLEBASE),
                        settings.get(ConfigConstants.LDAP_AUTHZ_ROLESEARCH, DEFAULT_ROLESEARCH)
                                .replace(LDAPAuthenticationBackend.ZERO_PLACEHOLDER, roleDn.toString())
                                .replace(ONE_PLACEHOLDER, roleDn.toString()), SearchScope.SUBTREE);

        if (log.isTraceEnabled()) {
            log.trace("result nested search count for depth {}: {}", depth, rolesResult==null?0:rolesResult.size());
        }

        
        if(rolesResult != null) {
            for (final LdapEntry entry : rolesResult) {
                try {
                    final LdapName dn = new LdapName(entry.getDn());
                    result.add(dn);
                } catch (final InvalidNameException e) {
                    throw new LdapException(e);
                }
            }
        }

        for (final LdapName nm : new HashSet<LdapName>(result)) {
            final Set<LdapName> in = resolveNestedRoles(nm, ldapConnection, userRoleName, depth, rolesearchEnabled);
            result.addAll(in);
        }

        return result;
    }

    @Override
    public String getType() {
        return "ldap";
    }

    private boolean isValidDn(final String dn) {

        if (Strings.isNullOrEmpty(dn)) {
            return false;
        }

        try {
            new LdapName(dn);
        } catch (final Exception e) {
            return false;
        }

        return true;
    }
    
    private String getRoleFromAttribute(final LdapName ldapName, final String role) {

        if (ldapName == null || Strings.isNullOrEmpty(role)) {
            return null;
        }

        if("dn".equalsIgnoreCase(role)) {
            return ldapName.toString();
        }
        
        List<Rdn> rdns = ldapName.getRdns();
        
        for(Rdn rdn: rdns) {
            if(role.equalsIgnoreCase(rdn.getType())) {
                
                if(rdn.getValue() == null) {
                    return null;
                }
                
                return String.valueOf(rdn.getValue());
            }
        }
        
        return null;
    }

}
