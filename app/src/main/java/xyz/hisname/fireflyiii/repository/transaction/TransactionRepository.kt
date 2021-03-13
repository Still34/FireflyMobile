/*
 * Copyright (c)  2018 - 2021 Daniel Quah
 * Copyright (c)  2021 ASDF Dev Pte. Ltd.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package xyz.hisname.fireflyiii.repository.transaction

import android.net.Uri
import androidx.paging.PagingSource
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import retrofit2.Response
import xyz.hisname.fireflyiii.Constants
import xyz.hisname.fireflyiii.data.local.dao.AttachmentDataDao
import xyz.hisname.fireflyiii.data.local.dao.TransactionDataDao
import xyz.hisname.fireflyiii.data.remote.firefly.api.TransactionService
import xyz.hisname.fireflyiii.repository.models.ApiResponses
import xyz.hisname.fireflyiii.repository.models.ObjectSum
import xyz.hisname.fireflyiii.repository.models.attachment.AttachmentData
import xyz.hisname.fireflyiii.repository.models.error.ErrorModel
import xyz.hisname.fireflyiii.repository.models.transaction.*
import xyz.hisname.fireflyiii.util.DateTimeUtil
import xyz.hisname.fireflyiii.util.extension.debounce
import xyz.hisname.fireflyiii.util.network.HttpConstants
import java.math.BigDecimal
import java.time.OffsetDateTime
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.math.abs
import kotlin.random.Random

@Suppress("RedundantSuspendModifier")
class TransactionRepository(private val transactionDao: TransactionDataDao,
                            private val transactionService: TransactionService) {

    suspend fun insertTransaction(transaction: Transactions){
        transactionDao.insert(transaction)
    }

    suspend fun insertTransaction(transactionIndex: TransactionIndex){
        transactionDao.insert(transactionIndex)
    }

    suspend fun transactionCount(startDate: String, endDate: String, source: String): Int {
        return transactionDao.getTransactionByDateCount(DateTimeUtil.getStartOfDayInCalendarToEpoch(startDate),
                DateTimeUtil.getEndOfDayInCalendarToEpoch(endDate), convertString(source))
    }

    suspend fun getTransactionByDateAndCurrencyCode(startDate: String, endDate: String,
                                                    currencyCode: String,
                                                    transactionType: String,
                                                    shouldLoadData: Boolean = true): BigDecimal{
        if(shouldLoadData){
            loadRemoteData(startDate, endDate, convertString(transactionType))
        }
        return transactionDao.getTransactionsByTypeWithDateAndCurrencyCode(DateTimeUtil.getStartOfDayInCalendarToEpoch(startDate),
                DateTimeUtil.getEndOfDayInCalendarToEpoch(endDate), convertString(transactionType), currencyCode)
    }

    suspend fun getTransactionSumByTagsAndTypeAndDateAndCurrency(tags: String, transactionType: String,
                                        startDate: String, endDate: String, currencyCode: String) =
            transactionDao.getTransactionSumByTagsAndTypeAndDateAndCurrency("%$tags%", convertString(transactionType),
                    DateTimeUtil.getStartOfDayInCalendarToEpoch(startDate),
                    DateTimeUtil.getEndOfDayInCalendarToEpoch(endDate), currencyCode)

    suspend fun getTransactionByDateAndBudgetAndCurrency(startDate: String, endDate: String,
                                                         currencyCode: String,
                                                         transactionType: String,
                                                         budgetName: String) =
        transactionDao.getTransactionByDateAndBudgetAndCurrency(DateTimeUtil.getStartOfDayInCalendarToEpoch(startDate),
                DateTimeUtil.getEndOfDayInCalendarToEpoch(endDate), currencyCode, transactionType, budgetName)


    suspend fun getUniqueBudgetByDate(startDate: String, endDate: String, currencyCode: String,
                                      transactionType: String)= transactionDao.getUniqueBudgetByDate(DateTimeUtil.getStartOfDayInCalendarToEpoch(startDate),
            DateTimeUtil.getEndOfDayInCalendarToEpoch(endDate), currencyCode, transactionType)

    suspend fun getTransactionByJournalId(journalId: Long) = transactionDao.getTransactionByJournalId(journalId)

    suspend fun getTemporaryAttachment(journalId: Long) = transactionDao.getAttachmentByJournalId(journalId)

    suspend fun getTransactionIdFromJournalId(journalId: Long) =
            transactionDao.getTransactionIdFromJournalId(journalId)

    suspend fun deleteTransactionById(transactionId: Long): Int {
        try {
            val networkResponse = transactionService.deleteTransactionById(transactionId)
            when(networkResponse.code()) {
                204 -> {
                    transactionDao.deleteTransactionByJournalId(transactionId)
                    return HttpConstants.NO_CONTENT_SUCCESS
                }
                401 -> {
                    /*   User is unauthenticated. We will retain user's data as we are
                     *   now in inconsistent state. This use case is unlikely to happen unless user
                     *   deletes their token from the web interface without updating the mobile client
                     */
                    return HttpConstants.UNAUTHORISED
                }
                404 -> {
                    // User probably deleted this on the web interface and tried to do it using mobile client
                    transactionDao.deleteTransactionByJournalId(transactionId)
                    return HttpConstants.NOT_FOUND
                }
                500 -> {
                    // When deleting transactions on some versions of Firefly, HTTP 500 is returned
                    transactionDao.deleteTransactionByJournalId(transactionId)
                    return HttpConstants.NO_CONTENT_SUCCESS
                }
                else -> {
                    return HttpConstants.FAILED
                }
            }
        } catch (exception: Exception) {
            transactionDao.deleteTransactionByJournalId(transactionId)
            return HttpConstants.FAILED
        }
    }

    suspend fun getDestinationAccountByTypeAndDate(startDate: String, endDate: String,
                                                   currencyCode: String,
                                                   transactionType: String) =
            transactionDao.getDestinationAccountByTypeAndDate(DateTimeUtil.getStartOfDayInCalendarToEpoch(startDate),
                    DateTimeUtil.getEndOfDayInCalendarToEpoch(endDate), currencyCode, convertString(transactionType))


    suspend fun getUniqueCategoryBySourceAndDateAndType(accountId: Long,
                                                        startDate: String, endDate: String,
                                                        transactionType: String) =
            transactionDao.getUniqueCategoryBySourceAndDateAndType(accountId,
                    DateTimeUtil.getStartOfDayInCalendarToEpoch(startDate),
                    DateTimeUtil.getEndOfDayInCalendarToEpoch(endDate),
                    transactionType)

    suspend fun getUniqueCategoryByDestinationAndDateAndType(accountId: Long,
                                                             startDate: String, endDate: String,
                                                             transactionType: String) =
            transactionDao.getUniqueCategoryByDestinationAndDateAndType(accountId,
                    DateTimeUtil.getStartOfDayInCalendarToEpoch(startDate),
                    DateTimeUtil.getEndOfDayInCalendarToEpoch(endDate),
                    transactionType)


    suspend fun getUniqueBudgetBySourceAndDateAndType(accountId: Long,
                                                      startDate: String, endDate: String,
                                                      transactionType: String) =
            transactionDao.getUniqueBudgetBySourceAndDateAndType(accountId,
                    DateTimeUtil.getStartOfDayInCalendarToEpoch(startDate),
                    DateTimeUtil.getEndOfDayInCalendarToEpoch(endDate), transactionType)

    suspend fun getUniqueBudgetByDestinationAndDateAndType(accountId: Long,
                                                           startDate: String, endDate: String,
                                                           transactionType: String) =
            transactionDao.getUniqueBudgetByDestinationAndDateAndType(accountId,
                    DateTimeUtil.getStartOfDayInCalendarToEpoch(startDate),
                    DateTimeUtil.getEndOfDayInCalendarToEpoch(endDate), transactionType)

    suspend fun getUniqueCategoryByDateAndType(startDate: String, endDate: String,
                                               currencyCode: String, transactionType: String): List<ObjectSum>{
        return transactionDao.getUniqueCategoryByDateAndType(DateTimeUtil.getStartOfDayInCalendarToEpoch(startDate),
                DateTimeUtil.getEndOfDayInCalendarToEpoch(endDate), currencyCode, convertString(transactionType))
    }

    suspend fun getUniqueBudgetByDateAndType(startDate: String, endDate: String,
                                             currencyCode: String, transactionType: String): List<ObjectSum>{
        return transactionDao.getUniqueBudgetByDateAndType(DateTimeUtil.getStartOfDayInCalendarToEpoch(startDate),
                DateTimeUtil.getEndOfDayInCalendarToEpoch(endDate), currencyCode, convertString(transactionType))
    }


    suspend fun getTransactionByDescription(query: String): List<String>{
        // Search via API only if query is more than 3
        if(query.length > 3){
            val handleSearch = debounce<String>(Dispatchers.IO){ debouncedString ->
                runBlocking {
                    try {
                        val networkCall = transactionService.searchTransaction(debouncedString)
                        val responseBody = networkCall.body()
                        if (responseBody != null && networkCall.isSuccessful) {
                            responseBody.data.forEach { data ->
                                data.transactionAttributes.transactions.forEach { transaction ->
                                    transactionDao.insert(transaction)
                                    transactionDao.insert(TransactionIndex(0, data.transactionId,
                                            transaction.transaction_journal_id,
                                            data.transactionAttributes.group_title))
                                }
                            }
                        }
                    } catch (exception: Exception){ }
                }
            }
            handleSearch(query)
        }
        return transactionDao.getTransactionByDescription("%$query%")
    }

    fun getPendingTransactionFromId(id: Long) = transactionDao.getPendingTransactionFromId(id)

    suspend fun deletePendingTransactionFromId(id: Long){
        transactionDao.getTempIdFromMasterId(id).forEach { transactionId ->
            transactionDao.deleteTransactionByJournalId(transactionId)
        }
        transactionDao.deleteTempMasterId(id)
    }

    suspend fun removeInternalReference(apiResponses: TransactionSuccessModel){
        val dynamicParams = HashMap<String, String>()
        apiResponses.data.transactionAttributes.transactions.forEachIndexed { index, transactions ->
            dynamicParams["transactions[$index][transaction_journal_id]"] = transactions.transaction_journal_id.toString()
            dynamicParams["transactions[$index][internal_reference]"] = ""
        }
        apiResponses.data
        transactionService.updateTransaction(apiResponses.data.transactionId,
                apiResponses.data.transactionAttributes.group_title, dynamicParams)
    }

    suspend fun addSplitTransaction(groupTitle: String,
                                    masterTransactionId: Long): ApiResponses<TransactionSuccessModel>{
        val dynamicParams = HashMap<String, String>()
        val tempTransactionList = transactionDao.getPendingTransactionFromMasterIdId(masterTransactionId)
        tempTransactionList.forEachIndexed { index, latest ->
            val transactionTags = if(latest.tags.isNullOrEmpty()){
                null
            } else {
                // Remove [ and ] from beginning and end of string
                val beforeTags = latest.tags.toString().substring(1)
                beforeTags.substring(0, beforeTags.length - 1)
            }
            dynamicParams["transactions[$index][type]"] = convertString(latest.transactionType)
            dynamicParams["transactions[$index][description]"] = latest.description
            dynamicParams["transactions[$index][date]"] = latest.date.toString()
            val piggyBankName = latest.piggy_bank_name
            if (piggyBankName != null){
                dynamicParams["transactions[$index][piggy_bank_name]"] = piggyBankName
            }
            dynamicParams["transactions[$index][amount]"] = latest.amount.toString()
            val source = latest.source_name
            if(source != null){
                dynamicParams["transactions[$index][source_name]"] = source
            }
            dynamicParams["transactions[$index][destination_name]"] = latest.destination_name
            dynamicParams["transactions[$index][currency_code]"] = latest.currency_code
            val category = latest.category_name
            if(category != null){
                dynamicParams["transactions[$index][category_name]"] = category
            }
            if (transactionTags != null){
                dynamicParams["transactions[$index][tags]"] = transactionTags
            }
            val budgetName = latest.budget_name
            if(budgetName != null){
                dynamicParams["transactions[$index][budget_name]"] = budgetName
            }
            val billName = latest.bill_name
            if(billName != null){
                dynamicParams["transactions[$index][bill_name]"] = billName
            }
            val note = latest.notes
            if(!note.isNullOrEmpty()){
                dynamicParams["transactions[$index][notes]"] = note
            }
            // !HACK!
            val attachment = latest.attachment
            if(!attachment.isNullOrEmpty()){
                dynamicParams["transactions[$index][internal_reference]"] = latest.transaction_journal_id.toString()
            }
        }
        val network = transactionService.addSplitTransaction(groupTitle, dynamicParams)
        return try {
             parseResponse(network)
        } catch (exception: Exception){
            ApiResponses(error = exception)
        }
    }

    // Individual fragment will store the response to DB
    suspend fun storeSplitTransaction(type: String, description: String,
                                      date: String, time: String?, piggyBankName: String?, amount: String,
                                      sourceName: String?, destinationName: String?, currencyName: String,
                                      category: String?, tags: String?, budgetName: String?, billName: String?,
                                      notes: String?, fileUri: ArrayList<Uri>, transactionMasterId: Long){
        val dateTime = if(time.isNullOrEmpty()){
            DateTimeUtil.offsetDateTimeWithoutTime(date)
        } else {
            DateTimeUtil.mergeDateTimeToIso8601(date, time)
        }
        val transactionAmount = if(amount.isEmpty()){
            0.0
        } else {
            amount.toDouble()
        }
        val tagsList = arrayListOf<String>()
        if(tags != null){
            tagsList.addAll(tags.split(",").map { it.trim() })
        }
        val fakeId = abs(Random.nextLong())
        val arrayOfString = arrayListOf<String>()
        fileUri.forEach {  uri ->
            arrayOfString.add(uri.toString())
        }
        transactionDao.insert(Transactions(
                fakeId, transactionAmount, 0, budgetName,
                0, category, currencyName, 0, 0, "",
                "", OffsetDateTime.parse(dateTime), description, 0, destinationName ?: "",
                "", 0, billName, "", 0.0, "",
                "", 0, "", notes, 0, "", 0,
                sourceName, "", "", tagsList, type, 0, piggyBankName, true, arrayOfString
        ))
        transactionDao.insert(TransactionIndex(
                0, transactionMasterId, fakeId, ""
        ))
    }

    @Deprecated("Switch to split transaction")
    suspend fun addTransaction(type: String, description: String,
                               date: String, time: String?, piggyBankName: String?, amount: String,
                               sourceName: String?, destinationName: String?, currencyName: String,
                               category: String?, tags: String?, budgetName: String?, billName: String?,
                               notes: String?): ApiResponses<TransactionSuccessModel> {
        val dateTime = if (time == null) {
            date
        } else {
            DateTimeUtil.mergeDateTimeToIso8601(date, time)
        }
        return try {
            val networkCall = transactionService.addTransaction(convertString(type), description, dateTime,
                    piggyBankName, amount.replace(',', '.'), sourceName,
                    destinationName, currencyName,
                    category, tags, budgetName, billName, notes)
            parseResponse(networkCall)
        } catch (exception: Exception){
            ApiResponses(error = exception)
        }
    }

    suspend fun updateTransaction(transactionId: Long, type: String, description: String,
                                  date: String, time: String?, piggyBankName: String?, amount: String,
                                  sourceName: String?, destinationName: String?, currencyName: String,
                                  category: String?, tags: String?, budgetName: String?, billName: String?,
                                  notes: String): ApiResponses<TransactionSuccessModel> {
        val dateTime = if (time == null) {
            date
        } else {
            DateTimeUtil.mergeDateTimeToIso8601(date, time)
        }
        return try {
            val networkCall = transactionService.updateTransaction(getTransactionIdFromJournalId(transactionId),
                    convertString(type), description, dateTime, piggyBankName,
                    amount.replace(',', '.'), sourceName, destinationName, currencyName,
                    category, tags, budgetName, billName, notes)
            parseResponse(networkCall)
        } catch (exception: Exception){
            ApiResponses(error = exception)
        }
    }

    suspend fun getAttachment(transactionJournalId: Long, attachmentDao: AttachmentDataDao): List<AttachmentData>{
        val transactionId = getTransactionIdFromJournalId(transactionJournalId)
        try {
            val networkCall = transactionService.getTransactionAttachment(transactionId)
            val responseBody = networkCall.body()
            if(responseBody != null && networkCall.isSuccessful){
                attachmentDao.deleteAttachmentFromJournalId(transactionJournalId)
                responseBody.data.forEach { attachmentData ->
                    attachmentDao.insert(attachmentData)
                }
            }
        } catch (exception: Exception) { }
        return attachmentDao.getAttachmentFromJournalId(transactionJournalId)
    }

    fun getTransactionByTagAndDate(startDate: String, endDate: String, tagName: String) = object : PagingSource<Int, Transactions>() {
        override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Transactions> {
            val transactionList = mutableListOf<Transactions>()
            try {
                loadRemoteData(startDate, endDate, "all")
                transactionList.addAll(transactionDao.getTransactionByDate(DateTimeUtil.getStartOfDayInCalendarToEpoch(startDate),
                        DateTimeUtil.getEndOfDayInCalendarToEpoch(endDate)))
                transactionList.forEachIndexed { index, transactions ->
                    if(!transactions.tags.contains(tagName)){
                        transactionList.removeAt(index)
                    }
                }
                } catch (exception: Exception){ }
                return LoadResult.Page(transactionList, params.key, 2)
            }
        override val keyReuseSupported = true
    }


    fun searchDescription(query: String) = object : PagingSource<Int, String>(){
        override suspend fun load(params: LoadParams<Int>): LoadResult<Int, String> {
            val paramKey = params.key
            val previousKey = if(paramKey != null){
                if(paramKey - 1 == 0){
                    null
                } else {
                    paramKey - 1
                }
            } else {
                null
            }
            if(query.isBlank()){
                return LoadResult.Page(transactionDao.getTransactionByDescription(), null, null)
            } else {
                try {
                    val networkCall = transactionService.searchTransaction(query)
                    val responseBody = networkCall.body()
                    if (responseBody != null && networkCall.isSuccessful) {
                        responseBody.data.forEach { data ->
                            data.transactionAttributes.transactions.forEach { transaction ->
                                transactionDao.insert(transaction)
                                transactionDao.insert(TransactionIndex(0, data.transactionId,
                                        transaction.transaction_journal_id,
                                        data.transactionAttributes.group_title))
                            }
                        }
                    }
                    val pagination = responseBody?.meta?.pagination
                    return if(pagination != null){
                        val nextKey = if(pagination.current_page < pagination.total_pages){
                            pagination.current_page + 1
                        } else {
                            null
                        }
                        LoadResult.Page(transactionDao.getTransactionByDescription("%$query%"),
                                previousKey, nextKey)
                    } else {
                        getDescriptionFromDatabase(params.key, previousKey, query)
                    }
                } catch (exception: Exception){
                    return getDescriptionFromDatabase(params.key, previousKey, query)
                }
            }
        }
        override val keyReuseSupported = true

    }

    private suspend fun getDescriptionFromDatabase(paramKey: Int?, previousKey: Int?, query: String): PagingSource.LoadResult<Int, String> {
        val numberOfRows = transactionDao.getTransactionByDescriptionCount("%$query%")
        val nextKey = if(paramKey ?: 1 < (numberOfRows / Constants.PAGE_SIZE)){
            paramKey ?: 1 + 1
        } else {
            null
        }
        return PagingSource.LoadResult.Page(transactionDao.getTransactionByDescription("%$query%"),
                previousKey, nextKey)

    }

    private suspend fun parseResponse(responseFromServer: Response<TransactionSuccessModel>): ApiResponses<TransactionSuccessModel>{
        val responseBody = responseFromServer.body()
        val responseErrorBody = responseFromServer.errorBody()
        if(responseBody != null && responseFromServer.isSuccessful){
            return ApiResponses(response = responseBody)
        } else {
            if(responseErrorBody != null){
                // Ignore lint warning. False positive
                // https://github.com/square/retrofit/issues/3255#issuecomment-557734546
                var errorMessage = String(responseErrorBody.bytes())
                val moshi = Moshi.Builder().build().adapter(ErrorModel::class.java).fromJson(errorMessage)
                errorMessage = when {
                    moshi?.errors?.transactions_currency != null -> moshi.errors.transactions_currency[0]
                    moshi?.errors?.piggy_bank_name != null -> moshi.errors.piggy_bank_name[0]
                    moshi?.errors?.transactions_destination_name != null -> moshi.errors.transactions_destination_name[0]
                    moshi?.errors?.transactions_source_name  != null -> moshi.errors.transactions_source_name[0]
                    moshi?.errors?.transactions_source_id != null -> moshi.errors.transactions_source_id[0]
                    moshi?.errors?.transaction_destination_id  != null -> moshi.errors.transaction_destination_id[0]
                    moshi?.errors?.transaction_amount != null -> moshi.errors.transaction_amount[0]
                    moshi?.errors?.description != null -> moshi.errors.description[0]
                    moshi?.errors?.transactions_budget_name != null -> moshi.errors.transactions_budget_name[0]
                    else -> moshi?.message ?: "The given data was invalid"
                }
                return ApiResponses(errorMessage = errorMessage)
            }
            return ApiResponses(errorMessage = "Error occurred while saving transactions")
        }
    }

    private suspend fun loadRemoteData(startDate: String?, endDate: String?, sourceName: String){
        try {
            val transactionData: MutableList<TransactionData> = arrayListOf()
            val networkCall = transactionService.getPaginatedTransactions(startDate, endDate,
                    convertString(sourceName), 1)
            networkCall.body()?.data?.forEach { transaction ->
                transactionData.add(transaction)
            }
            val responseBody = networkCall.body()
            if (responseBody != null && networkCall.isSuccessful) {
                val pagination = responseBody.meta.pagination
                if (pagination.total_pages != pagination.current_page) {
                    for (items in 2..pagination.total_pages) {
                        val service = transactionService.getPaginatedTransactions(startDate, endDate,
                                convertString(sourceName), items).body()
                        service?.data?.forEach { dataToBeAdded ->
                            transactionData.add(dataToBeAdded)
                        }
                    }
                }
                deleteTransactionsByDate(startDate, endDate, sourceName)
                transactionData.forEach { data ->
                    data.transactionAttributes.transactions.forEach { transaction ->
                        transactionDao.insert(transaction)
                        transactionDao.insert(TransactionIndex(0, data.transactionId,
                                transaction.transaction_journal_id,
                                data.transactionAttributes.group_title))
                    }
                }
            }
        } catch (exception: Exception){ }
    }

    private suspend fun deleteTransactionsByDate(startDate: String?, endDate: String?, transactionType: String): Int{
        return if(startDate == null || endDate == null || startDate.isBlank() || endDate.isBlank()){
            transactionDao.deleteTransaction()
        } else {
            transactionDao.deleteTransactionsByDate(DateTimeUtil.getStartOfDayInCalendarToEpoch(startDate),
                    DateTimeUtil.getEndOfDayInCalendarToEpoch(endDate), transactionType)
        }
    }

    private fun convertString(type: String) = type.substring(0, 1).toLowerCase() + type.substring(1).toLowerCase()

}