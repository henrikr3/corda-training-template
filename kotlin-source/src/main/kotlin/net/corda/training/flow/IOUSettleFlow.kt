package net.corda.training.flow

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.*
import net.corda.core.flows.*
import net.corda.core.identity.PartyAndCertificate
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.OpaqueBytes
import net.corda.finance.contracts.asset.Cash
import net.corda.finance.contracts.asset.Cash.Companion.generateSpend
import net.corda.finance.contracts.getCashBalance
import net.corda.finance.flows.CashIssueFlow
import net.corda.training.contract.IOUContract
import net.corda.training.state.IOUState
import java.lang.IllegalArgumentException
import java.util.*

/**
 * This is the flow which handles the (partial) settlement of existing IOUs on the ledger.
 * Gathering the counterparty's signature is handled by the [CollectSignaturesFlow].
 * Notarisation (if required) and commitment to the ledger is handled vy the [FinalityFlow].
 * The flow returns the [SignedTransaction] that was committed to the ledger.
 */
@InitiatingFlow
@StartableByRPC
class IOUSettleFlow(val linearId: UniqueIdentifier, val amount: Amount<Currency>): FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call(): SignedTransaction {
        // Placeholder code to avoid type error when running the tests. Remove before starting the flow task!
        val queryCriteria = QueryCriteria.LinearStateQueryCriteria(linearId = listOf(linearId))
        val iouStateAndRef = serviceHub.vaultService.queryBy<IOUState>(queryCriteria).states.single()
        val state = iouStateAndRef.state.data

        if(state.borrower != ourIdentity) {
            throw java.lang.IllegalArgumentException("Only borrower can execute this flow!")
        }

        val cashBalance = serviceHub.getCashBalance(amount.token)
        if(cashBalance < amount) {
            throw IllegalArgumentException("There should be some cash available.")
        }

        if((cashBalance.quantity > (state.amount - state.paid).quantity)) {
            throw IllegalArgumentException("There has to be enough cash to pay the borrower.")
        }

        val notary = this.serviceHub.networkMapCache.notaryIdentities.single()
        val command = Command(IOUContract.Commands.Settle(), (state.participants).map { it.owningKey })

        val transactionBuilder = TransactionBuilder(notary).withItems(iouStateAndRef, command)

        val transaction = Cash.generateSpend(serviceHub, transactionBuilder, amount, this.ourIdentityAndCert, state.lender, onlyFromParties = setOf(ourIdentity))
        val transactionBuilderWithPay = transaction.first

        val outputState = state.pay(amount)
        transactionBuilderWithPay.addOutputState(outputState, IOUContract.IOU_CONTRACT_ID)

        transactionBuilderWithPay.verify(serviceHub)
        val signedTransaction = serviceHub.signInitialTransaction(transactionBuilderWithPay)
        val sessionsToCollectFrom = (state.participants - ourIdentity).asSequence().map { initiateFlow(it) }.toSet()
        val stx = subFlow(CollectSignaturesFlow(signedTransaction, sessionsToCollectFrom))

        return subFlow(FinalityFlow(stx))
    }
}

/**
 * This is the flow which signs IOU settlements.
 * The signing is handled by the [SignTransactionFlow].
 */
@InitiatedBy(IOUSettleFlow::class)
class IOUSettleFlowResponder(val flowSession: FlowSession): FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        val signedTransactionFlow = object : SignTransactionFlow(flowSession) {
            override fun checkTransaction(stx: SignedTransaction) = requireThat {
                val outputStates = stx.tx.outputs.map { it.data::class.java.name }.toList()
                "There must be an IOU transaction." using (outputStates.contains(IOUState::class.java.name))
            }
        }

        subFlow(signedTransactionFlow)
    }
}

@InitiatingFlow
@StartableByRPC
/**
 * Self issues the calling node an amount of cash in the desired currency.
 * Only used for demo/sample/training purposes!
 */
class SelfIssueCashFlow(val amount: Amount<Currency>) : FlowLogic<Cash.State>() {
    @Suspendable
    override fun call(): Cash.State {
        /** Create the cash issue command. */
        val issueRef = OpaqueBytes.of(0)
        /** Note: ongoing work to support multiple notary identities is still in progress. */
        val notary = serviceHub.networkMapCache.notaryIdentities.first()
        /** Create the cash issuance transaction. */
        val cashIssueTransaction = subFlow(CashIssueFlow(amount, issueRef, notary))
        /** Return the cash output. */
        return cashIssueTransaction.stx.tx.outputs.single().data as Cash.State
    }
}