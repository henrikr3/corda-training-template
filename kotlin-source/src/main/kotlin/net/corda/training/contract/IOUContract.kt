package net.corda.training.contract

import net.corda.core.contracts.*
import net.corda.core.identity.Party
import net.corda.core.transactions.LedgerTransaction
import net.corda.finance.USD
import net.corda.finance.contracts.asset.CASH
import net.corda.finance.contracts.asset.Cash
import net.corda.training.state.IOUState
import java.util.*
import net.corda.finance.utils.sumCash

/**
 * This is where you'll add the contract code which defines how the [IOUState] behaves. Look at the unit tests in
 * [IOUContractTests] for instructions on how to complete the [IOUContract] class.
 */
class IOUContract : Contract {
    companion object {
        @JvmStatic
        val IOU_CONTRACT_ID = "net.corda.training.contract.IOUContract"
    }

    /**
     * Add any commands required for this contract as classes within this interface.
     * It is useful to encapsulate your commands inside an interface, so you can use the [requireSingleCommand]
     * function to check for a number of commands which implement this interface.
     */
    interface Commands : CommandData {
        // Add commands here.
        // E.g
        // class DoSomething : TypeOnlyCommandData(), Commands
        class Issue : TypeOnlyCommandData(), Commands
        class Transfer : TypeOnlyCommandData(), Commands
        class Settle : TypeOnlyCommandData(), Commands
    }

    /**
     * The contract code for the [IOUContract].
     * The constraints are self documenting so don't require any additional explanation.
     */
    override fun verify(tx: LedgerTransaction) {
        // Add contract code here.
        // requireThat {
        //     ...
        //
        val command = tx.commands.requireSingleCommand<Commands>()
        when (command.value) {
            is Commands.Issue -> requireThat{
                "No inputs should be consumed when issuing an IOU." using (tx.inputs.size == 0)
                "Only one output state should be created when issuing an IOU." using (tx.outputs.size == 1)
                val state = tx.outputStates.single() as IOUState
                "A newly issued IOU must have a positive amount." using (state.amount.quantity > 0)
                "The lender and borrower cannot have the same identity." using (!state.lender.equals(state.borrower))
                "Both lender and borrower together only may sign IOU issue transaction." using
                        (command.signers.toSet() == state.participants.map { it.owningKey }.toSet())
            }
            is Commands.Transfer -> requireThat{
                "An IOU transfer transaction should only consume one input state." using (tx.inputs.size == 1)
                "An IOU transfer transaction should only create one output state." using (tx.outputs.size == 1)
                val inputState = tx.inputStates.single() as IOUState
                val outputState = tx.outputStates.single() as IOUState
                val inputStateCopy = inputState.copy(
                        amount = inputState.amount,
                        lender = outputState.lender,
                        borrower = inputState.borrower,
                        paid = inputState.paid,
                        linearId = inputState.linearId)
                "Only the lender property may change." using (inputStateCopy.equals(outputState))
                "The lender property must change in a transfer." using (!inputState.lender.equals(outputState.lender))
                "The borrower, old lender and new lender only must sign an IOU transfer transaction" using
                        (command.signers.toSet() == (inputState.participants.map { it.owningKey }.toSet() `union`
                        outputState.participants.map { it.owningKey }.toSet()))

            }
            is Commands.Settle-> requireThat{
                val ious = tx.groupStates<IOUState, UniqueIdentifier> { it.linearId }.single()
                "There must be one input IOU." using (ious.inputs.size == 1)
                val cash = tx.outputsOfType(Cash.State::class.java)
                "There must be output cash." using (cash.isNotEmpty())
                val input = ious.inputs.single()
                val lenderCash = cash.filter { it.owner == input.lender }
                "There must be output cash paid to the recipient." using (lenderCash.isNotEmpty())

                val cashLeftToBePaid = input.amount - input.paid
                val totalLenderCash = lenderCash.sumCash().withoutIssuer()
                "The amount settled cannot be more than the amount outstanding." using (cashLeftToBePaid >= totalLenderCash)
                "Token mismatch: GBP vs USD" using (input.paid.token == input.amount.token)

                if(cashLeftToBePaid == totalLenderCash) {
                    "There must be no output IOU as it has been fully settled." using (ious.outputs.size == 0)
                } else {
                    "There must be one output IOU." using (ious.outputs.size == 1)
                    val inputState = ious.inputs.single() as IOUState
                    val outputState = ious.outputs.single() as IOUState
                    "The borrower may not change when settling." using (inputState.borrower == outputState.borrower)
                    "The amount may not change when settling." using (inputState.amount == outputState.amount)
                    "The lender may not change when settling." using (inputState.lender == outputState.lender)
                    val inputStateCopy = inputState.copy(
                            amount = inputState.amount,
                            lender = inputState.lender,
                            borrower = inputState.borrower,
                            paid = outputState.paid,
                            linearId = inputState.linearId)
                    "Only the paid property may change." using (inputStateCopy == outputState)

                    "Both lender and borrower together only must sign IOU settle transaction." using
                            (command.signers.toSet() == (inputState.participants.map { it.owningKey }.toSet() `union`
                                    outputState.participants.map { it.owningKey }.toSet()))
                }
            }
        }
    }
}
