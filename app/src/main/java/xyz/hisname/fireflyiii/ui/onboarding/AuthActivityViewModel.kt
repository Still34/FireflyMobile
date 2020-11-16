package xyz.hisname.fireflyiii.ui.onboarding

import android.accounts.AccountManager
import android.app.Application
import android.net.Uri
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import retrofit2.Response
import xyz.hisname.fireflyiii.Constants
import xyz.hisname.fireflyiii.data.local.account.AuthenticatorManager
import xyz.hisname.fireflyiii.data.local.dao.AppDatabase
import xyz.hisname.fireflyiii.data.local.pref.AppPref
import xyz.hisname.fireflyiii.data.remote.firefly.FireflyClient
import xyz.hisname.fireflyiii.data.remote.firefly.api.AccountsService
import xyz.hisname.fireflyiii.data.remote.firefly.api.OAuthService
import xyz.hisname.fireflyiii.repository.BaseViewModel
import xyz.hisname.fireflyiii.repository.account.AccountRepository
import xyz.hisname.fireflyiii.repository.models.auth.AuthModel
import xyz.hisname.fireflyiii.util.FileUtils
import xyz.hisname.fireflyiii.util.extension.isAscii
import java.net.UnknownServiceException
import java.security.cert.CertificateException

class AuthActivityViewModel(application: Application): BaseViewModel(application) {

    val isShowingBack: MutableLiveData<Boolean> = MutableLiveData()
    val baseUrl: MutableLiveData<String> = MutableLiveData()
    val isAuthenticated: MutableLiveData<Boolean> = MutableLiveData()
    val showInfoMessage: MutableLiveData<String> = MutableLiveData()
    val showErrorMessage: MutableLiveData<String> = MutableLiveData()

    private val applicationContext = getApplication<Application>()
    private val accountManager = AccountManager.get(applicationContext)
    private val accountDao by lazy { AppDatabase.getInstance(applicationContext).accountDataDao() }
    private var accountsService: AccountsService? = null
    private lateinit var repository: AccountRepository
    private val oAuthService by lazy { genericService()?.create(OAuthService::class.java) }


    fun authViaPat(baseUrl: String, accessToken: String, fileUri: Uri?) {
        if(accessToken.isEmpty()){
            showInfoMessage.postValue("Personal Access Token Required!")
            return
        }
        if(baseUrl.isEmpty()){
            showInfoMessage.postValue("Base URL Required!")
            return
        }
        isLoading.postValue(true)
        if(fileUri != null && fileUri.toString().isNotBlank()) {
            FileUtils.saveCaFile(fileUri, getApplication())
        }
        authInit(accessToken, baseUrl)
        repository = AccountRepository(accountDao, accountsService)
        viewModelScope.launch(Dispatchers.IO){
            try {
                repository.authViaPat()
                AuthenticatorManager(accountManager).authMethod = "pat"
                isAuthenticated.postValue(true)
            } catch (exception: UnknownServiceException){
                FileUtils.deleteCaFile(applicationContext)
                showErrorMessage.postValue("http is not supported. Please use https")
                isAuthenticated.postValue(false)
            } catch (certificateException: CertificateException){
                FileUtils.deleteCaFile(applicationContext)
                showErrorMessage.postValue("Are you using self signed cert?")
                isAuthenticated.postValue(false)
            } catch (exception: Exception){
                FileUtils.deleteCaFile(applicationContext)
                showErrorMessage.postValue(exception.localizedMessage)
                isAuthenticated.postValue(false)
            }
            isLoading.postValue(false)
        }
    }


    fun authViaOauth(baseUrl: String, clientSecret: String, clientId: String, fileUri: Uri?): Boolean{
        if(baseUrl.isEmpty()){
            showInfoMessage.postValue("Base URL Required!")
            return false
        }
        if(clientSecret.isEmpty()){
            showInfoMessage.postValue("Client Secret Required!")
            return false
        }
        if(clientId.isEmpty()){
            showInfoMessage.postValue("Client ID Required!")
            return false
        }
        if(fileUri != null && fileUri.toString().isNotBlank()) {
            FileUtils.saveCaFile(fileUri, getApplication())
        }
        authInit("", baseUrl)
        accManager.clientId = clientId
        accManager.secretKey = clientSecret
        return true
    }

    fun getAccessToken(code: String, isDemo: Boolean = false){
        isLoading.postValue(true)
        if (!code.isAscii()) {
            // Issue #46 on Github
            // https://github.com/emansih/FireflyMobile/issues/46
            isAuthenticated.postValue(false)
            showErrorMessage.postValue("Bearer Token contains invalid Characters!")
        } else {
            var networkCall: Response<AuthModel>?
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val redirectUri = if(isDemo){
                        Constants.DEMO_REDIRECT_URI
                    } else {
                        Constants.REDIRECT_URI
                    }
                    networkCall = oAuthService?.getAccessToken(code.trim(), accManager.clientId,
                            accManager.secretKey, redirectUri)
                    val authResponse = networkCall?.body()
                    val errorBody = networkCall?.errorBody()
                    if (authResponse != null && networkCall?.isSuccessful != false) {
                        accManager.accessToken = authResponse.access_token.trim()
                        accManager.refreshToken = authResponse.refresh_token.trim()
                        accManager.tokenExpiry = authResponse.expires_in
                        accManager.authMethod = "oauth"
                        isAuthenticated.postValue(true)
                    } else if(errorBody != null){
                        FileUtils.deleteCaFile(applicationContext)
                        val errorBodyMessage = String(errorBody.bytes())
                        showErrorMessage.postValue(errorBodyMessage)
                        isAuthenticated.postValue(false)
                    }
                } catch (exception: UnknownServiceException){
                    FileUtils.deleteCaFile(applicationContext)
                    showErrorMessage.postValue("http is not supported. Please use https")
                    isAuthenticated.postValue(false)
                } catch (certificateException: CertificateException){
                    FileUtils.deleteCaFile(applicationContext)
                    showErrorMessage.postValue("Are you using self signed cert?")
                    isAuthenticated.postValue(false)
                } catch (exception: Exception){
                    FileUtils.deleteCaFile(applicationContext)
                    showErrorMessage.postValue(exception.localizedMessage)
                    isAuthenticated.postValue(false)
                }
            }
            isLoading.postValue(false)
        }
    }

    fun setDemo(code: String){
        authInit("", "https://demo.firefly-iii.org")
        accManager.clientId = "2"
        accManager.secretKey = "tfWoJQbmV88Fxej1ysAPIxFireflyIIIApiToken"
        getAccessToken(code, true)
    }

    private fun authInit(accessToken: String, baseUrl: String){
        FireflyClient.destroyInstance()
        accManager.destroyAccount()
        AuthenticatorManager(accountManager).initializeAccount()
        AuthenticatorManager(accountManager).accessToken = accessToken.trim()
        AppPref(sharedPref).baseUrl = baseUrl
    }
}