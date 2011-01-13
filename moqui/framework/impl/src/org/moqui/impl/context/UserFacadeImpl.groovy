/*
 * This Work is in the public domain and is provided on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied,
 * including, without limitation, any warranties or conditions of TITLE,
 * NON-INFRINGEMENT, MERCHANTABILITY, or FITNESS FOR A PARTICULAR PURPOSE.
 * You are solely responsible for determining the appropriateness of using
 * this Work and assume any risks associated with your use of this Work.
 *
 * This Work includes contributions authored by David E. Jones, not as a
 * "work for hire", who hereby disclaims any copyright to the same.
 */
package org.moqui.impl.context

import java.sql.Timestamp
import javax.servlet.http.HttpServletRequest

import org.moqui.context.UserFacade
import org.moqui.context.WebExecutionContext
import org.moqui.entity.EntityValue
import org.moqui.impl.StupidUtilities

import org.slf4j.Logger
import org.slf4j.LoggerFactory

class UserFacadeImpl implements UserFacade {
    protected final static Logger logger = LoggerFactory.getLogger(UserFacadeImpl.class)

    protected ExecutionContextImpl eci
    protected Timestamp effectiveTime = null

    // just keep the userId, always get the UserAccount value from the entity cache
    protected Deque<String> userIdStack = new LinkedList()

    // there may be non-web visits, so keep a copy of the visitId here
    protected String visitId = null

    // we mostly want this for the Locale default, and may be useful for other things
    protected HttpServletRequest request = null

    UserFacadeImpl(ExecutionContextImpl eci) {
        this.eci = eci
    }

    void initFromHttpRequest(HttpServletRequest request) {
        this.request = request
        if (request.session.getAttribute("moqui.userId")) {
            // effectively login the user
            String userId = (String) request.session.getAttribute("moqui.userId")
            // better not to do this, if there was a user before this init leave it for history/debug: if (this.userIdStack) this.userIdStack.pop()
            if (this.userIdStack.size() == 0 || this.userIdStack.peek() != userId) this.userIdStack.push(userId)
        }
        if (request.session.getAttribute("moqui.visitId")) {
            this.visitId = (String) request.session.getAttribute("moqui.visitId")
        }
    }

    /** @see org.moqui.context.UserFacade#getLocale() */
    Locale getLocale() {
        Locale locale = null
        if (this.userId) {
            String localeStr = this.userAccount.locale
            if (localeStr) locale = new Locale(localeStr)
        }
        return (locale ?: (request ? request.getLocale() : Locale.getDefault()))
    }

    /** @see org.moqui.context.UserFacade#setLocale(Locale) */
    void setLocale(Locale locale) {
        if (this.userId) {
            eci.service.sync().name("update", "UserAccount")
                    .context((Map<String, Object>) [userId:getUserId(), locale:locale.toString()]).call()
        } else {
            throw new IllegalStateException("No user logged in, can't set Locale")
        }
    }

    /** @see org.moqui.context.UserFacade#getTimeZone() */
    TimeZone getTimeZone() {
        TimeZone tz = null
        if (this.userId) {
            String tzStr = this.userAccount.timeZone
            if (tzStr) tz = TimeZone.getTimeZone(tzStr)
        }
        return tz ?: TimeZone.getDefault()
    }

    /** @see org.moqui.context.UserFacade#setTimeZone(TimeZone) */
    void setTimeZone(TimeZone tz) {
        if (this.userId) {
            eci.service.sync().name("update", "UserAccount")
                    .context((Map<String, Object>) [userId:getUserId(), timeZone:tz.getID()]).call()
        } else {
            throw new IllegalStateException("No user logged in, can't set Time Zone")
        }
    }

    /** @see org.moqui.context.UserFacade#getCurrencyUomId() */
    String getCurrencyUomId() { return this.userId ? this.userAccount.currencyUomId : null }

    /** @see org.moqui.context.UserFacade#setCurrencyUomId(String) */
    void setCurrencyUomId(String uomId) {
        if (this.userId) {
            eci.service.sync().name("update", "UserAccount")
                    .context((Map<String, Object>) [userId:getUserId(), currencyUomId:uomId]).call()
        } else {
            throw new IllegalStateException("No user logged in, can't set Currency")
        }
    }

    /** @see org.moqui.context.UserFacade#getNowTimestamp() */
    Timestamp getNowTimestamp() {
        // TODO: review Timestamp and nowTimestamp use, have things use this by default
        return this.effectiveTime ? this.effectiveTime : new Timestamp(System.currentTimeMillis())
    }

    /** @see org.moqui.context.UserFacade#setEffectiveTime(Timestamp) */
    void setEffectiveTime(Timestamp effectiveTime) { this.effectiveTime = effectiveTime }

    boolean loginUser(String userId, String password) {
        boolean successful = false
        if (authenticateUser(userId, password)) {
            successful = true

            EntityValue newUserAccount = eci.entity.makeFind("UserAccount").condition("userId", userId).useCache(true).one()

            // if hasLoggedOut==Y set hasLoggedOut=N
            if (newUserAccount.hasLoggedOut == "Y") {
                eci.service.sync().name("update", "UserAccount")
                        .context((Map<String, Object>) [userId:userId, hasLoggedOut:"N"]).call()
            }

            // update visit if no user in visit yet
            if (this.visit && !this.visit.userId) {
                eci.service.sync().name("update", "Visit")
                        .context((Map<String, Object>) [visitId:getVisitId(), userId:userId]).call()
            }

            // if WebExecutionContext add to session
            if (eci.ecfi.getExecutionContext() instanceof WebExecutionContext) {
                WebExecutionContext wec = (WebExecutionContext) eci.ecfi.getExecutionContext()
                wec.getSession().setAttribute("moqui.userId", newUserAccount.userId)
            }

            // just in case there is already a user authenticated push onto a stack to remember
            this.userIdStack.push((String) newUserAccount.userId)
        }

        Node loginNode = eci.ecfi.confXmlRoot."user-facade"[0]."login"[0]

        // track the UserLoginHistory
        if (loginNode."@history-store" != "false") {
            Map<String, Object> ulhContext =
                    (Map<String, Object>) [userId:userId, visitId:getVisitId(), successfulLogin:(successful?"Y":"N")]
            if (!successful && loginNode."@history-incorrect-password" != "false") ulhContext.passwordUsed = password
            eci.service.sync().name("create", "UserLoginHistory").context(ulhContext).call()
        }

        return successful
    }

    /** @see org.moqui.context.UserFacade#authenticateUser(String, String) */
    boolean authenticateUser(String userId, String password) {
        EntityValue newUserAccount = eci.entity.makeFind("UserAccount").condition("userId", userId).useCache(true).one()
        if (!newUserAccount) return false

        // check encrypted/hashed password
        String passedInHash = StupidUtilities.getHashDigest(password,
                StupidUtilities.getHashSaltFromFull((String) newUserAccount.currentPassword),
                StupidUtilities.getHashTypeFromFull((String) newUserAccount.currentPassword))
        // just compare the hash part of the full string
        if (StupidUtilities.getHashHashFromFull(passedInHash) !=
                StupidUtilities.getHashHashFromFull((String) newUserAccount.currentPassword)) {
            // only if failed on password, increment in new transaction to make sure it sticks
            eci.service.sync().name("org.moqui.impl.UserServices.incrementUserAccountFailedLogins")
                    .context((Map<String, Object>) [userId:userId]).requireNewTransaction(true).call()
            return false
        }

        Map<String, Object> uaContext = (Map<String, Object>) [userId:userId, successiveFailedLogins:0]
        if (newUserAccount.requirePasswordChange == "Y") {
            throw new IllegalStateException("Authenticate failed for user [${userId}] because account requires password change [PWDCHG].")
        }
        if (newUserAccount.disabled == "Y") {
            Timestamp reEnableTime = null
            if (newUserAccount.disabledDateTime) {
                Integer disabledMinutes = eci.ecfi.confXmlRoot."user-facade"[0]."login"[0]."@disable-minutes" ?: 30 as Integer
                reEnableTime = new Timestamp(newUserAccount.getTimestamp("disabledDateTime").getTime() + (disabledMinutes*60*1000))
            }
            if (!reEnableTime || reEnableTime < getNowTimestamp()) {
                throw new IllegalStateException("Authenticate failed for user [${userId}] because account is disabled and will not be re-enabled until [${reEnableTime}] [ACTDIS].")
            } else {
                uaContext.disabled = "N"
                uaContext.disabledDateTime = null
            }
        }

        // check time since password was last changed, if it has been too long (user-facade.password.@change-weeks default 12) then fail
        if (newUserAccount.passwordSetDate) {
            int changeWeeks = eci.ecfi.confXmlRoot."user-facade"[0]."password"[0]."@change-weeks" ?: 12 as int
            int wksSinceChange = (eci.user.nowTimestamp.time - newUserAccount.passwordSetDate.time) / (7*24*60*60*1000)
            if (wksSinceChange > changeWeeks) {
                throw new IllegalStateException("Authenticate failed for user [${userId}] because password was changed [${wksSinceChange}] weeks ago and should be changed every [${changeWeeks}] weeks [PWDTIM].")
            }
        }

        // no more auth failures? record the various account state updates
        eci.service.sync().name("update", "UserAccount").context(uaContext).call()

        return true
    }

    void logoutUser() {
        if (this.userIdStack) this.userIdStack.pop()
    }

    /* @see org.moqui.context.UserFacade#getUserId() */
    String getUserId() { return this.userIdStack ? this.userIdStack.peek() : null }

    /* @see org.moqui.context.UserFacade#getUserAccount() */
    EntityValue getUserAccount() {
        if (!userIdStack) return null
        return eci.entity.makeFind("UserAccount").condition("userId", userIdStack.peek()).useCache(true).one()
    }

    /** @see org.moqui.context.UserFacade#getVisitUserId() */
    String getVisitUserId() { return visitId ? getVisit().userId : null }

    /** @see org.moqui.context.UserFacade#getVisitId() */
    String getVisitId() { return visitId }

    /** @see org.moqui.context.UserFacade#getVisit() */
    EntityValue getVisit() {
        if (!visitId) return null
        return eci.entity.makeFind("Visit").condition("visitId", visitId).useCache(true).one()
    }
}
