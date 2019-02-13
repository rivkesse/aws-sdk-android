package com.amazonaws.mobile.client;

import android.content.Context;
import android.support.test.InstrumentationRegistry;
import android.util.Log;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.mobile.client.results.SignInResult;
import com.amazonaws.mobile.client.results.SignInState;
import com.amazonaws.mobile.client.results.SignUpResult;
import com.amazonaws.mobile.client.results.Token;
import com.amazonaws.mobile.client.results.Tokens;
import com.amazonaws.mobile.client.results.UserCodeDeliveryDetails;
import com.amazonaws.mobile.config.AWSConfiguration;
import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.cognitoidentity.AmazonCognitoIdentity;
import com.amazonaws.services.cognitoidentity.AmazonCognitoIdentityClient;
import com.amazonaws.services.cognitoidentity.model.GetOpenIdTokenForDeveloperIdentityRequest;
import com.amazonaws.services.cognitoidentity.model.GetOpenIdTokenForDeveloperIdentityResult;
import com.amazonaws.services.cognitoidentityprovider.AmazonCognitoIdentityProvider;
import com.amazonaws.services.cognitoidentityprovider.AmazonCognitoIdentityProviderClient;
import com.amazonaws.services.cognitoidentityprovider.model.AdminConfirmSignUpRequest;
import com.amazonaws.services.cognitoidentityprovider.model.DeleteUserRequest;
import com.amazonaws.services.cognitoidentityprovider.model.ListUsersRequest;
import com.amazonaws.services.cognitoidentityprovider.model.ListUsersResult;
import com.amazonaws.services.cognitoidentityprovider.model.UserType;
import com.amazonaws.services.cognitoidentityprovider.model.UsernameExistsException;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Userpool and identity pool were create with Amplify CLI 0.1.23 Default configuration
 */
public class AWSMobileClientTest extends AWSMobileClientTestBase {
    private static final String TAG = AWSMobileClientTest.class.getSimpleName();

    public static final String EMAIL = "somebody@email.com";
    public static final String BLURRED_EMAIL = "s***@e***.com";
    public static final String USERNAME = "somebody";
    public static final String PASSWORD = "1234Password!";
    public static final String IDENTITY_ID = "redacted-mobile-client-identity-id";
    public static final String NEW_PASSWORD = "new1234Password!";
    public static final int THROTTLED_DELAY = 5000;

    static BasicAWSCredentials adminCreds = new BasicAWSCredentials("redacted-mobile-client-cognito-admin-access-key"
            , "redacted-mobile-client-cognito-admin-secret-key");

    // Populated from awsconfiguration.json
    static Regions clientRegion = Regions.US_WEST_2;
    static String userPoolId;
    static String identityPoolId;

    Context appContext;
    AWSMobileClient auth;
    UserStateListener listener;
    String username;

    public static void createUser(final AWSMobileClient auth,
                                  final String userpoolId,
                                  final String username,
                                  final String password,
                                  final String email) throws Exception {
        HashMap<String, String> userAttributes = new HashMap<String, String>();
        userAttributes.put("email", email);
        auth.signUp(username, password, userAttributes,null);

        AmazonCognitoIdentityProvider userpool = new AmazonCognitoIdentityProviderClient(adminCreds);
        userpool.setRegion(Region.getRegion("us-west-2"));

        AdminConfirmSignUpRequest adminConfirmSignUpRequest = new AdminConfirmSignUpRequest();
        adminConfirmSignUpRequest.withUsername(username).withUserPoolId(userpoolId);
        userpool.adminConfirmSignUp(adminConfirmSignUpRequest);
    }

    public static void deleteAllUsers(final String userpoolId) {
        AmazonCognitoIdentityProvider userpool = new AmazonCognitoIdentityProviderClient(adminCreds);
        userpool.setRegion(Region.getRegion("us-west-2"));
        ListUsersResult listUsersResult;
        do {
            ListUsersRequest listUsersRequest = new ListUsersRequest()
                    .withUserPoolId(userpoolId)
                    .withLimit(60);
            listUsersResult = userpool.listUsers(listUsersRequest);
            for (UserType user : listUsersResult.getUsers()) {
                if (USERNAME.equals(user.getUsername())) {
                    // This user is saved to test the identity id permanence
                    continue;
                }
                try {
                    AWSMobileClient.getInstance().signIn(user.getUsername(), PASSWORD, null);
                    DeleteUserRequest deleteUserRequest = new DeleteUserRequest()
                            .withAccessToken(AWSMobileClient.getInstance().getTokens().getAccessToken().getTokenString());
                    userpool.deleteUser(deleteUserRequest);
                } catch (Exception e) {
                    Log.e(TAG, "deleteAllUsers: Some error trying to delete user", e);
                }
            }
        } while (listUsersResult.getPaginationToken() != null);
    }

    @BeforeClass
    public static void beforeClass() throws Exception {
        Context appContext = InstrumentationRegistry.getTargetContext();
        final CountDownLatch latch = new CountDownLatch(1);
        AWSMobileClient.getInstance().initialize(appContext, new Callback<UserStateDetails>() {
            @Override
            public void onResult(UserStateDetails result) {
                latch.countDown();
            }

            @Override
            public void onError(Exception e) {
                latch.countDown();
            }
        });
        latch.await();

        final AWSConfiguration awsConfiguration = AWSMobileClient.getInstance().getConfiguration();

        JSONObject userPoolConfig = awsConfiguration.optJsonObject("CognitoUserPool");
        assertNotNull(userPoolConfig);
        clientRegion = Regions.fromName(userPoolConfig.getString("Region"));
        userPoolId = userPoolConfig.getString("PoolId");

        JSONObject identityPoolConfig =
                awsConfiguration.optJsonObject("CredentialsProvider").getJSONObject(
                        "CognitoIdentity").getJSONObject("Default");
        assertNotNull(identityPoolConfig);
        identityPoolId = identityPoolConfig.getString("PoolId");

        deleteAllUsers(userPoolId);
    }

    @Before
    public void before() throws Exception {
        appContext = InstrumentationRegistry.getTargetContext();
        auth = AWSMobileClient.getInstance();
        auth.signOut();

        username = "testUser" + System.currentTimeMillis() + new Random().nextInt();
        createUser(auth, username, userPoolId, PASSWORD, EMAIL);
    }

    @After
    public void after() {
        auth.removeUserStateListener(listener);
        auth.listeners.clear();
    }

    @Test
    public void useAppContext() throws Exception {
        // Context of the app under test.
        Context appContext = InstrumentationRegistry.getTargetContext();

        final AWSConfiguration awsConfiguration = new AWSConfiguration(appContext);

        assertNotNull(awsConfiguration.optJsonObject("CognitoUserPool"));
        assertEquals("us-west-2", awsConfiguration.optJsonObject("CognitoUserPool").getString("Region"));

        assertEquals("com.amazonaws.mobile.client.test", appContext.getPackageName());
    }

    @Test
    public void testGetConfiguration() throws JSONException {
        final AWSConfiguration awsConfiguration = AWSMobileClient.getInstance().getConfiguration();

        assertNotNull(awsConfiguration.optJsonObject("CognitoUserPool"));
        try {
            assertEquals("us-west-2", awsConfiguration.optJsonObject("CognitoUserPool").getString("Region"));
        } catch (JSONException e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test(expected = com.amazonaws.services.cognitoidentityprovider.model.UserNotConfirmedException.class)
    public void testSignUp() throws Exception {
        final String username = "testUser" + System.currentTimeMillis() + new Random().nextInt();
        assertNotEquals("generated usernames are the same", this.username, username);

        final HashMap<String, String> userAttributes = new HashMap<String, String>();
        userAttributes.put("email", EMAIL);

        final SignUpResult signUpResult = auth.signUp(username, PASSWORD, userAttributes, null);
        if (signUpResult.getConfirmationState()) {
            // Done
        } else {
            final UserCodeDeliveryDetails details = signUpResult.getUserCodeDeliveryDetails();
            assertEquals(BLURRED_EMAIL, details.getDestination());
            assertEquals("email", details.getAttributeName());
            assertEquals("EMAIL", details.getDeliveryMedium());
        }
        final SignInResult signInResult = auth.signIn(username, PASSWORD, null);
        if (signInResult.getSignInState() == SignInState.DONE) {
            // Done
        } else {
            fail("Cannot support MFA in tests");
        }
    }

    @Test
    public void testSignIn() throws Exception {
        final CountDownLatch stateNotificationLatch = new CountDownLatch(1);
        final AtomicReference<UserStateDetails> userState = new AtomicReference<UserStateDetails>();
        listener = new UserStateListener() {
            @Override
            public void onUserStateChanged(UserStateDetails details) {
                userState.set(details);
                auth.removeUserStateListener(listener);
                stateNotificationLatch.countDown();
            }
        };
        auth.addUserStateListener(listener);

        final SignInResult signInResult = auth.signIn(username, PASSWORD, null);
        if (signInResult.getSignInState() == SignInState.DONE) {
            // Done
        } else {
            fail("Cannot support MFA in tests");
        }

        assertTrue("isSignedIn is true", auth.isSignedIn());

        assertEquals(username, auth.getUsername());

        // Check credentials are available
        final AWSCredentials credentials = auth.getCredentials();
        assertNotNull("Credentials are null", credentials);
        assertNotNull("Access key is null", credentials.getAWSAccessKeyId());
        assertNotNull("Secret key is null", credentials.getAWSSecretKey());

        Tokens tokens = auth.getTokens();
        assertNotNull(tokens);

        Token accessToken = tokens.getAccessToken();
        assertNotNull(accessToken);
        assertTrue("Access token should not be expired", accessToken.getExpiration().after(new Date()));
        Token idToken = tokens.getIdToken();
        assertNotNull(idToken);
        assertTrue("Id token should not be expired", idToken.getExpiration().after(new Date()));
        Token refreshToken = tokens.getRefreshToken();
        assertNotNull(refreshToken);

        // Check one attribute
        final Map<String, String> userAttributes = auth.getUserAttributes();
        assertEquals(EMAIL, userAttributes.get("email"));
        stateNotificationLatch.await(5, TimeUnit.SECONDS);

        UserStateDetails userStateDetails = userState.get();
        assertEquals(userStateDetails.getUserState(), UserState.SIGNED_IN);
        Map<String, String> details = userStateDetails.getDetails();
        assertNotEquals(IDENTITY_ID, details.toString());
    }

    @Test
    public void testIdentityId() throws Exception {
        try {
            createUser(AWSMobileClient.getInstance(), USERNAME, PASSWORD, EMAIL);
            fail("The user should already exist in the userpool. Otherwise this test cannot determine whether the identity id was changed.");
        } catch (UsernameExistsException e) {
            // If the person exists, this is expected.
        }

        auth.signIn(USERNAME, PASSWORD, null);
        // Populate the identity id
        auth.getCredentials();
        assertEquals("Identity id should not change between SDK releases", IDENTITY_ID, auth.getIdentityId());
    }

    @Test
    public void testSignInWaitFederated() throws Exception {
        final AtomicReference<Boolean> hasWaited = new AtomicReference<Boolean>();
        hasWaited.set(false);
        setTokensDirectly(appContext, "fakeProvider", "fakeToken", "someIdentityId");
        listener = new UserStateListener() {
            @Override
            public void onUserStateChanged(UserStateDetails details) {
                switch (details.getUserState()) {
                    case SIGNED_OUT_FEDERATED_TOKENS_INVALID:
                        try {
                            hasWaited.set(true);
                            auth.signIn(USERNAME, PASSWORD, null);
                        } catch (Exception e) {
                            e.printStackTrace();
                            fail("Sign-in failed, but not expected.");
                        }
                        break;
                }
            }
        };
        auth.addUserStateListener(listener);

        AWSCredentials credentials = auth.getCredentials();
        assertNotNull(credentials);
        assertNotNull(credentials.getAWSAccessKeyId());
        assertNotNull(credentials.getAWSSecretKey());
        assertTrue("Should have waited, but didn't", hasWaited.get());
    }

    @Test
    public void testSignInWaitOIDC() throws Exception {
        final AtomicReference<Boolean> hasWaited = new AtomicReference<Boolean>();
        hasWaited.set(false);
       // writeUserpoolsTokens(appContext, auth.getConfiguration().optJsonObject("CognitoUserPool").getString("AppClientId"), USERNAME, 3600L);
        setTokensDirectly(appContext, AWSMobileClient.getInstance().getLoginKey(), "fakeToken", "someIdentityId");
        listener = new UserStateListener() {
            @Override
            public void onUserStateChanged(UserStateDetails details) {
                switch (details.getUserState()) {
                    case SIGNED_OUT_USER_POOLS_TOKENS_INVALID:
                        try {
                            if (hasWaited.get()) {
                                fail("Multiple calls to state change");
                            }
                            hasWaited.set(true);
                            auth.signIn(username, PASSWORD, null);
                        } catch (Exception e) {
                            e.printStackTrace();
                            fail("Sign-in failed, but not expected.");
                        }
                        break;
                    default:
                        if (!hasWaited.get()) {
                            fail("Sign-in before waiting");
                        }
                }
            }
        };
        auth.addUserStateListener(listener);
        assertTrue("User is online and tokens are invalid", auth.isSignedIn());

        auth.getTokens();
        assertTrue("Should have waited, but didn't", hasWaited.get());
    }

    @Test
    public void testSignOut() throws Exception {
        final SignInResult signInResult = auth.signIn(username, PASSWORD, null);
        if (signInResult.getSignInState() == SignInState.DONE) {
            // Done
        } else {
            fail("Cannot support MFA in tests");
        }

        AWSCredentials credentials1 = auth.getCredentials();
        auth.signOut();

        // Test identity id has been cleared
        assertNull(auth.getIdentityId());

        // Test username has been cleared
        assertNull(auth.getUsername());

        // Check credentials need to be fetched
        try {
            AWSCredentials credentials = auth.getCredentials();
            fail("Unauthenticated access is not supported for this identity pool in this test\n" + credentials.getAWSAccessKeyId() + "\n" + credentials1.getAWSAccessKeyId());
        } catch (RuntimeException e) {
            assertTrue(e.getCause() instanceof com.amazonaws.services.cognitoidentity.model.NotAuthorizedException);
        }

        // Check tokens are gone
        try {
            assertNull(auth.getTokens());
        } catch (Exception e) {
            assertEquals("getTokens does not support retrieving tokens while signed-out", e.getMessage());
        }
    }

    @Test(expected = com.amazonaws.services.cognitoidentityprovider.model.NotAuthorizedException.class)
    public void testSignInWrongPassword() throws Exception {
        AWSMobileClient.getInstance().signIn("bimin", "wrong", null);
    }

    @Test
    public void testFederate() {

    }

    // Changing a password tends to have a rate limit that exceeds test timeout
//    @Test
    public void testChangePassword() throws Exception {
        Thread.sleep(THROTTLED_DELAY);
        auth.changePassword(PASSWORD, NEW_PASSWORD);
        auth.signOut();
        Thread.sleep(THROTTLED_DELAY);
        auth.signIn("bimin", NEW_PASSWORD, null);
        Thread.sleep(THROTTLED_DELAY);
        auth.changePassword(NEW_PASSWORD, PASSWORD);
        auth.signOut();
        Thread.sleep(THROTTLED_DELAY);
        auth.signIn("bimin", PASSWORD, null);
    }

    @Test
    public void testAddRemoveListeners() throws Exception {
        final AtomicReference<Boolean> triggered = new AtomicReference<Boolean>();
        triggered.set(false);
        final CountDownLatch countDownLatch = new CountDownLatch(1);
        UserStateListener listenerA = new UserStateListener() {
            @Override
            public void onUserStateChanged(UserStateDetails details) {
                triggered.set(true);
                countDownLatch.countDown();
            }
        };
        UserStateListener listenerB = new UserStateListener() {
            @Override
            public void onUserStateChanged(UserStateDetails details) {

            }
        };
        auth.addUserStateListener(listenerA);
        auth.addUserStateListener(listenerB);
        assertEquals(2, auth.listeners.size());
        auth.removeUserStateListener(listenerA);
        auth.signIn(username, PASSWORD, null);
        countDownLatch.await(5, TimeUnit.SECONDS);
        assertFalse(triggered.get());
    }

    @Test
    public void testFederatedSignInWithDeveloperAuthenticatedIdentities() throws Exception {
        AmazonCognitoIdentity identityClient = new AmazonCognitoIdentityClient(adminCreds);
        identityClient.setRegion(Region.getRegion("us-west-2"));

        GetOpenIdTokenForDeveloperIdentityRequest request =
                new GetOpenIdTokenForDeveloperIdentityRequest();
        request.setIdentityPoolId(identityPoolId);

        HashMap<String,String> logins = new HashMap<String, String>();
        logins.put("foo.bar","john.doe");
        request.setLogins(logins);

        GetOpenIdTokenForDeveloperIdentityResult response =
                identityClient.getOpenIdTokenForDeveloperIdentity(request);

        final String identityId = response.getIdentityId();
        final String token = response.getToken();

        FederatedSignInOptions options =
                FederatedSignInOptions.builder().identityId(identityId).build();

        UserStateDetails userStateDetails =
                auth.federatedSignIn(IdentityProvider.DEVELOPER.toString(), token, options);

        assertEquals(UserState.SIGNED_IN, userStateDetails.getUserState());

        assertNotNull("Credentials from federated sign-in should not be null", auth.getCredentials());
    }

    @Ignore("This test case may cause crash on some emulators")
    @Test
    public void testGetTokensStress() throws Exception {
        final SignInResult signInResult = auth.signIn(username, PASSWORD, null);
        if (signInResult.getSignInState() == SignInState.DONE) {
            // Done
        } else {
            fail("Cannot support MFA in tests");
        }
        auth.addUserStateListener(new UserStateListener() {
            @Override
            public void onUserStateChanged(UserStateDetails details) {
                Log.d(TAG, "onUserStateChanged: " + details.getUserState());
            }
        });

        ArrayList<Thread> threads = new ArrayList<Thread>(10);
        final AtomicReference<Boolean> stop = new AtomicReference<Boolean>(false);
        for (int i = 0; i < 10; ++i) {
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    String lastMessage = "";
                    while (!stop.get()) {
                        try {
                            auth.getTokens();
                        } catch (Exception e) {
                            if (!lastMessage.equals(e.getMessage())) {
                                lastMessage = e.getMessage();

                                if (!lastMessage.equals("No cached session.")
                                        && !lastMessage.equals("getTokens does not support retrieving tokens while signed-out")) {
                                    fail("Unexpected error message: " + lastMessage);
                                }
                            }
                        }
                    }
                }
            });
            thread.start();
            threads.add(thread);
        }

        Thread.sleep(2 * 1000);
        Log.d(TAG, "testStress: signOut");
        auth.signOut();
        Thread.sleep(1 * 1000);
        stop.set(true);
        for (Thread t : threads) {
            t.join();
        }
    }

    @Test
    public void testGetCredentialsStress() throws Exception {
        final SignInResult signInResult = auth.signIn(username, PASSWORD, null);
        if (signInResult.getSignInState() == SignInState.DONE) {
            // Done
        } else {
            fail("Cannot support MFA in tests");
        }
        auth.addUserStateListener(new UserStateListener() {
            @Override
            public void onUserStateChanged(UserStateDetails details) {
                Log.d(TAG, "onUserStateChanged: " + details.getUserState());
            }
        });

        ArrayList<Thread> threads = new ArrayList<Thread>(10);
        final AtomicReference<Boolean> stop = new AtomicReference<Boolean>(false);
        for (int i = 0; i < 10; ++i) {
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    String lastMessage = "";
                    while (!stop.get()) {
                        try {
                            auth.getCredentials();
                        } catch (Exception e) {
                            if (!lastMessage.equals(e.getMessage())) {
                                lastMessage = e.getMessage();

                                if (!lastMessage.equals("Failed to get credentials from Cognito Identity")) {
                                    fail("Unexpected error message: " + lastMessage);
                                }
                            }
                        }
                    }
                }
            });
            thread.start();
            threads.add(thread);
        }

        Thread.sleep(2 * 1000);
        Log.d(TAG, "testStress: signOut");
        auth.signOut();
        Thread.sleep(1 * 1000);
        stop.set(true);
        for (Thread t : threads) {
            t.join();
        }
    }

}
