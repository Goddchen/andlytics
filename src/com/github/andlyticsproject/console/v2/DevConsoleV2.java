package com.github.andlyticsproject.console.v2;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.util.Log;
import com.github.andlyticsproject.console.AuthenticationException;
import com.github.andlyticsproject.console.DevConsole;
import com.github.andlyticsproject.console.DevConsoleException;
import com.github.andlyticsproject.console.NetworkException;
import com.github.andlyticsproject.model.AppInfo;
import com.github.andlyticsproject.model.AppStats;
import com.github.andlyticsproject.model.Comment;
import org.apache.http.HttpStatus;
import org.apache.http.client.CookieStore;
import org.apache.http.client.HttpResponseException;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.cookie.Cookie;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * This is a WIP class representing the new v2 version of the developer console.
 * The aim is to build it from scratch to make it a light weight and as well
 * documented at the end as possible. Once it is done and available to all
 * users, we will rip out the old code and replace it with this.
 *
 * Once v2 is available to all users, there is scope for better utilising the
 * available statistics data, so keep that in mind when developing this class.
 * For now though, keep it simple and get it working.
 *
 * See https://github.com/AndlyticsProject/andlytics/wiki/Developer-Console-v2
 * for some more documentation
 *
 * This class fetches the data, which is then passed using {@link JsonParser}
 *
 */
@SuppressLint("DefaultLocale")
public class DevConsoleV2 implements DevConsole {

	// 30 seconds -- for both socket and connection
	public static final int TIMEOUT = 30 * 1000;

	private static final String TAG = DevConsoleV2.class.getSimpleName();

	private static final boolean DEBUG = false;

	private DefaultHttpClient httpClient;
	private DevConsoleAuthenticator authenticator;
	private String accountName;
	private DevConsoleV2Protocol protocol;

	private ResponseHandler<String> responseHandler = HttpClientFactory.createResponseHandler();

	public static DevConsoleV2 createForAccount(String accountName, DefaultHttpClient httpClient) {
		DevConsoleAuthenticator authenticator = new AccountManagerAuthenticator(accountName,
				httpClient);

		return new DevConsoleV2(httpClient, authenticator, new DevConsoleV2Protocol());
	}

	public static DevConsoleV2 createForAccountAndPassword(String accountName, String password,
			DefaultHttpClient httpClient) {
		DevConsoleAuthenticator authenticator = new PasswordAuthenticator(accountName, password,
				httpClient);

		return new DevConsoleV2(httpClient, authenticator, new DevConsoleV2Protocol());
	}

	private DevConsoleV2(DefaultHttpClient httpClient, DevConsoleAuthenticator authenticator,
			DevConsoleV2Protocol protocol) {
		this.httpClient = httpClient;
		this.authenticator = authenticator;
		this.accountName = authenticator.getAccountName();
		this.protocol = protocol;
	}

	/**
	 * Gets a list of available apps for the given account
	 *
	 * @param activity
	 * @return
	 * @throws DevConsoleException
	 */
	public synchronized List<AppInfo> getAppInfo(Activity activity) throws DevConsoleException {
		try {
			// the authenticator launched a sub-activity, bail out for now
			if (!authenticateWithCachedCredentialas(activity)) {
				return new ArrayList<AppInfo>();
			}

			return fetchAppInfosAndStatistics();
		} catch (AuthenticationException ex) {
			if (!authenticateFromScratch(activity)) {
				return new ArrayList<AppInfo>();
			}

			return fetchAppInfosAndStatistics();
		}
	}

	private List<AppInfo> fetchAppInfosAndStatistics() {
		// Fetch a list of available apps
		List<AppInfo> apps = fetchAppInfos();

		for (AppInfo app : apps) {
			// Fetch remaining app statistics
			// Latest stats object, and active/total installs is fetched
			// in fetchAppInfos
			AppStats stats = app.getLatestStats();
			fetchRatings(app, stats);
			stats.setNumberOfComments(fetchCommentsCount(app));
		}

		return apps;
	}

	/**
	 * Gets a list of comments for the given app based on the startIndex and
	 * count
	 *
	 * @param accountName
	 * @param packageName
	 * @param startIndex
	 * @param count
	 * @return
	 * @throws DevConsoleException
	 */
	public synchronized List<Comment> getComments(Activity activity, String packageName,
			int whichDevAccount, int startIndex, int count) throws DevConsoleException {
		try {
			if (!authenticateWithCachedCredentialas(activity)) {
				return new ArrayList<Comment>();
			}

			return fetchComments(packageName, whichDevAccount, startIndex, count);
		} catch (AuthenticationException ex) {
			if (!authenticateFromScratch(activity)) {
				return new ArrayList<Comment>();
			}

			return fetchComments(packageName, whichDevAccount, startIndex, count);
		}
	}

	/**
	 * Fetches a list of apps for the given account
	 *
	 * @param accountName
	 * @return
	 * @throws DevConsoleException
	 */
	private List<AppInfo> fetchAppInfos() throws DevConsoleException {
        List<AppInfo> appInfos = new ArrayList<AppInfo>();
        for(int i=0;i<protocol.getSessionCredentials().getDeveloperAccountIds().length; i++) {
            String response = post(protocol.createFetchAppsUrl(i), protocol.createFetchAppInfosRequest(i), i);
            List<AppInfo> currentAppInfos = protocol.parseAppInfosResponse(response,  accountName);
            for(AppInfo appInfo : currentAppInfos) {
                appInfo.whichDevAccount = i;
            }
            appInfos.addAll(currentAppInfos);
        }
        return appInfos;
	}

	/**
	 * Fetches statistics for the given packageName of the given statsType and
	 * adds them to the given {@link AppStats} object
	 *
	 * This is not used as statistics can be fetched via fetchAppInfos Can use
	 * it later to get historical etc data
	 *
	 * @param packageName
	 * @param stats
	 * @param statsType
	 * @throws DevConsoleException
	 */
	@SuppressWarnings("unused")
	private void fetchStatistics(AppInfo appInfo, AppStats stats, int statsType)
			throws DevConsoleException {
        for(int i=0;i<protocol.getSessionCredentials().getDeveloperAccountIds().length;i++) {
            if(appInfo.whichDevAccount == i) {
                String response = post(protocol.createFetchStatisticsUrl(i), protocol.createFetchStatisticsRequest
                        (appInfo.getPackageName(),  statsType), i);
                protocol.parseStatisticsResponse(response, stats, statsType);
            }
        }
	}

	/**
	 * Fetches ratings for the given packageName and adds them to the given
	 * {@link AppStats} object
	 *
	 * @param packageName
	 *            The app to fetch ratings for
	 * @param stats
	 *            The AppStats object to add them to
	 * @throws DevConsoleException
	 */
	private void fetchRatings(AppInfo appInfo, AppStats stats) throws DevConsoleException {
        for(int i=0;i<protocol.getSessionCredentials().getDeveloperAccountIds().length;i++) {
            if(appInfo.whichDevAccount == i) {
                String response = post(protocol.createFetchCommentsUrl(i), protocol.createFetchRatingsRequest
                        (appInfo.getPackageName()), i);
                protocol.parseRatingsResponse(response, stats);
            }
        }
	}

	/**
	 * Fetches the number of comments for the given packageName
	 *
	 * @param packageName
	 * @return
	 * @throws DevConsoleException
	 */
	private int fetchCommentsCount(AppInfo appInfo) throws DevConsoleException {
		// TODO -- this doesn't always produce correct results
		// emulate the console: fetch first 50, get approx num. comments,
		// fetch last 50 (or so) to get exact number.
        int finalNumComments = 0;
        for(int i=0;i<protocol.getSessionCredentials().getDeveloperAccountIds().length;i++) {
            if(appInfo.whichDevAccount == i) {
                int pageSize = 50;

                String response = post(protocol.createFetchCommentsUrl(i),
                        protocol.createFetchCommentsRequest(appInfo.getPackageName(), 0, pageSize), i);
                int approxNumComments = protocol.extractCommentsCount(response);
                if (approxNumComments <= pageSize) {
                    // this has a good chance of being exact
                    return approxNumComments;
                }

                response = post(protocol.createFetchCommentsUrl(i), protocol.createFetchCommentsRequest(
                        appInfo.getPackageName(), approxNumComments - pageSize, pageSize), i);
                finalNumComments += protocol.extractCommentsCount(response);
            }
        }

		return finalNumComments;
	}

	private List<Comment> fetchComments(String packageName, int whichDevAccount, int startIndex, int count)
			throws DevConsoleException {
        List<Comment> comments = new ArrayList<Comment>();
        for(int i=0;i<protocol.getSessionCredentials().getDeveloperAccountIds().length;i++) {
            if(whichDevAccount == i) {
                String response = post(protocol.createFetchCommentsUrl(i),
                        protocol.createFetchCommentsRequest(packageName, startIndex, count), i);
                comments.addAll(protocol.parseCommentsResponse(response));
            }
        }
		return comments;
	}

	private boolean authenticateWithCachedCredentialas(Activity activity) {
		return authenticate(activity, false);
	}

	private boolean authenticateFromScratch(Activity activity) {
		return authenticate(activity, true);
	}

	/**
	 * Logs into the Android Developer Console
	 *
	 * @param reuseAuthentication
	 * @throws DevConsoleException
	 */
	private boolean authenticate(Activity activity, boolean invalidateCredentials)
			throws DevConsoleException {
		if (invalidateCredentials) {
			protocol.invalidateSessionCredentials();
		}

		if (protocol.hasSessionCredentials()) {
			// nothing to do
			return true;
		}

		SessionCredentials sessionCredentials = activity == null ? authenticator
				.authenticateSilently(invalidateCredentials) : authenticator.authenticate(activity,
				invalidateCredentials);
		protocol.setSessionCredentials(sessionCredentials);

		return protocol.hasSessionCredentials();
	}

	private String post(String url, String postData, int whichDevAccount) {
		try {
			HttpPost post = new HttpPost(url);
			protocol.addHeaders(post, whichDevAccount);
			post.setEntity(new StringEntity(postData, "UTF-8"));

			if (DEBUG) {
				CookieStore cookieStore = httpClient.getCookieStore();
				List<Cookie> cookies = cookieStore.getCookies();
				for (Cookie c : cookies) {
					Log.d(TAG, String.format("****Cookie**** %s=%s", c.getName(), c.getValue()));
				}
			}

			return httpClient.execute(post, responseHandler);
		} catch (HttpResponseException e) {
			if (e.getStatusCode() == HttpStatus.SC_UNAUTHORIZED) {
				throw new AuthenticationException(e);
			}

			throw new NetworkException(e);
		} catch (IOException e) {
			throw new NetworkException(e);
		}

	}

}
