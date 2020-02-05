package com.atlassian.bitbucket.jenkins.internal.applink.oauth.rest;

import com.atlassian.bitbucket.jenkins.internal.applink.oauth.adaptor.OAuthConverter;
import com.atlassian.bitbucket.jenkins.internal.applink.oauth.common.Consumer;
import com.atlassian.bitbucket.jenkins.internal.applink.oauth.provider.*;
import com.atlassian.bitbucket.jenkins.internal.applink.oauth.provider.temp.InMemoryConsumerStore;
import com.atlassian.bitbucket.jenkins.internal.applink.oauth.provider.temp.ServiceProviderTokenStoreImpl;
import com.atlassian.bitbucket.jenkins.internal.applink.oauth.provider.temp.TokenFactoryImpl;
import hudson.model.InvisibleAction;
import net.oauth.*;
import net.oauth.server.OAuthServlet;
import org.kohsuke.stapler.WebMethod;
import org.kohsuke.stapler.interceptor.RequirePOST;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.atlassian.bitbucket.jenkins.internal.applink.oauth.util.OAuthProblemUtils.logOAuthProblem;
import static java.util.Arrays.asList;
import static net.oauth.OAuth.*;
import static net.oauth.OAuth.Problems.*;
import static net.oauth.server.OAuthServlet.handleException;

public class RequestTokenRestEndpoint extends InvisibleAction {

    public static final String INVALID_CALLBACK_ADVICE =
            "As per OAuth spec version 1.0 Revision A Section 6.1 <http://oauth.net/core/1.0a#auth_step1>, the " +
            "oauth_callback parameter is required and must be either a valid, absolute URI using the http or https scheme, " +
            "or 'oob' if the callback has been established out of band. The following invalid URI was supplied '%s'";

    private static final Logger LOGGER = Logger.getLogger(RequestTokenRestEndpoint.class.getName());

    private OAuthValidator oAuthValidator;
    private ConsumerStore consumerStore;
    private TokenFactory tokenFactory;
    private ServiceProviderTokenStore tokenStore;

    public RequestTokenRestEndpoint() {
        oAuthValidator = new SimpleOAuthValidator();
        consumerStore = new InMemoryConsumerStore();
        tokenFactory = new TokenFactoryImpl();
        tokenStore = new ServiceProviderTokenStoreImpl();
    }

    public RequestTokenRestEndpoint(OAuthValidator oAuthValidator,
                                    ConsumerStore consumerStore,
                                    TokenFactory tokenFactory,
                                    ServiceProviderTokenStore tokenStore) {
        this.oAuthValidator = oAuthValidator;
        this.consumerStore = consumerStore;
        this.tokenFactory = tokenFactory;
        this.tokenStore = tokenStore;
    }

    @RequirePOST
    @WebMethod(name = "request-token")
    public void handleRequestToken(HttpServletRequest req,
                                   HttpServletResponse resp) throws ServletException, IOException {
        try {
            OAuthMessage message = OAuthServlet.getMessage(req, null);
            message.requireParameters(OAUTH_CONSUMER_KEY);
            Consumer consumer = consumerStore.get(message.getConsumerKey());
            if (consumer == null) {
                throw new OAuthProblemException(CONSUMER_KEY_UNKNOWN);
            }

            try {
                oAuthValidator.validateMessage(message, new OAuthAccessor(OAuthConverter.toOAuthConsumer(consumer)));
            } catch (OAuthProblemException ope) {
                logOAuthProblem(message, ope, LOGGER);
                throw ope;
            }
            URI callback = null;
            if (message.getParameter(OAUTH_CALLBACK) != null) {
                callback = callbackToUri(message.getParameter(OAUTH_CALLBACK));
            }

            Token token = tokenStore.put(tokenFactory.generateRequestToken(consumer, callback, message));
            // We form encode the output, but it's not standard form encoding, it's OAuth form encoding.  The main
            // difference seems to be that OAuth doesn't encode spaces as '+'. See
            // <http://groups.google.com/group/oauth/browse_thread/thread/ef2455dc89546222>.  This means we can't use
            // application/x-www-form-urlencoded as you might expect, we need to use text/plain
            resp.setContentType("text/plain");
            OutputStream out = resp.getOutputStream();
            List<Parameter> parameters = asList(
                    new Parameter(OAUTH_TOKEN, token.getToken()),
                    new Parameter(OAUTH_TOKEN_SECRET, token.getTokenSecret()),
                    new Parameter(OAUTH_CALLBACK_CONFIRMED, "true"));
            formEncode(parameters, out);
        } catch (Exception e) {
            handleException(resp, e, req.getRequestURL().toString(), true);
        }
    }

    private URI callbackToUri(String callbackParameter) throws IOException, OAuthProblemException {
        if (callbackParameter.equals("oob")) {
            return null;
        }
        URI callback;
        try {
            callback = new URI(callbackParameter);
        } catch (URISyntaxException e) {
            LOGGER.log(Level.SEVERE, "Unable to parse callback URI '%s'", callbackParameter);
            OAuthProblemException problem = new OAuthProblemException(PARAMETER_REJECTED);
            problem.setParameter(OAUTH_PARAMETERS_REJECTED, OAUTH_CALLBACK);
            problem.setParameter(OAUTH_PROBLEM_ADVICE, String.format(INVALID_CALLBACK_ADVICE, callbackParameter));
            throw problem;
        }
        if (!ServiceProviderToken.isValidCallback(callback)) {
            LOGGER.log(Level.SEVERE, "Invalid callback URI '%s'", callbackParameter);
            OAuthProblemException problem = new OAuthProblemException(PARAMETER_REJECTED);
            problem.setParameter(OAUTH_PARAMETERS_REJECTED, OAUTH_CALLBACK);
            problem.setParameter(OAUTH_PROBLEM_ADVICE, String.format(INVALID_CALLBACK_ADVICE, callbackParameter));
            throw problem;
        }
        return callback;
    }
}