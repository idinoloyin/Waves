package com.wavesplatform.state

import com.wavesplatform.account.{Address, AddressOrAlias, Alias}
import com.wavesplatform.block.Block.BlockId
import com.wavesplatform.block.{Block, BlockHeader, SignedBlockHeader}
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.consensus.GeneratingBalanceProvider
import com.wavesplatform.lang.ValidationError
import com.wavesplatform.lang.script.Script
import com.wavesplatform.settings.BlockchainSettings
import com.wavesplatform.state.reader.LeaseDetails
import com.wavesplatform.transaction.Asset.{IssuedAsset, Waves}
import com.wavesplatform.transaction.TxValidationError.AliasDoesNotExist
import com.wavesplatform.transaction.lease.LeaseTransaction
import com.wavesplatform.transaction.transfer.TransferTransaction
import com.wavesplatform.transaction.{Asset, Transaction}

trait Blockchain {
  def settings: BlockchainSettings

  def height: Int
  def score: BigInt

  def blockHeader(height: Int): Option[SignedBlockHeader]
  def hitSource(height: Int): Option[ByteStr]

  def carryFee: Long

  def heightOf(blockId: ByteStr): Option[Int]

  /** Features related */
  def approvedFeatures: Map[Short, Int]
  def activatedFeatures: Map[Short, Int]
  def featureVotes(height: Int): Map[Short, Int]

  /** Block reward related */
  def blockReward(height: Int): Option[Long]
  def blockRewardVotes(height: Int): Seq[Long]

  def wavesAmount(height: Int): BigInt

  def transferById(id: ByteStr): Option[(Int, TransferTransaction)]
  def transactionInfo(id: ByteStr): Option[(Int, Transaction)]
  def transactionHeight(id: ByteStr): Option[Int]

  def containsTransaction(tx: Transaction): Boolean

  def assetDescription(id: IssuedAsset): Option[AssetDescription]

  def resolveAlias(a: Alias): Either[ValidationError, Address]

  def leaseDetails(leaseId: ByteStr): Option[LeaseDetails]

  def filledVolumeAndFee(orderId: ByteStr): VolumeAndFee

  /** Retrieves Waves balance snapshot in the [from, to] range (inclusive) */
  def balanceSnapshots(address: Address, from: Int, to: BlockId): Seq[BalanceSnapshot]

  def accountScript(address: Address): Option[AccountScriptInfo]
  def hasAccountScript(address: Address): Boolean

  def assetScript(id: IssuedAsset): Option[(Script, Long)]

  def accountData(acc: Address, key: String): Option[DataEntry[_]]

  def leaseBalance(address: Address): LeaseBalance

  def balance(address: Address, mayBeAssetId: Asset = Waves): Long

  def collectActiveLeases(filter: LeaseTransaction => Boolean): Seq[LeaseTransaction]

  /** Builds a new portfolio map by applying a partial function to all portfolios on which the function is defined.
    *
    * @note Portfolios passed to `pf` only contain Waves and Leasing balances to improve performance */
  def collectLposPortfolios[A](pf: PartialFunction[(Address, Portfolio), A]): Map[Address, A]
}

object Blockchain {
  implicit class BlockchainExt(private val blockchain: Blockchain) extends AnyVal {
    def isEmpty: Boolean = blockchain.height == 0

    def parentHeader(block: BlockHeader, back: Int = 1): Option[BlockHeader] =
      blockchain.heightOf(block.reference).map(_ - (back - 1).max(0)).flatMap(h => blockchain.blockHeader(h).map(_.header))

    def contains(block: Block): Boolean       = blockchain.contains(block.uniqueId)
    def contains(signature: ByteStr): Boolean = blockchain.heightOf(signature).isDefined

    def blockId(atHeight: Int): Option[ByteStr] = blockchain.blockHeader(atHeight).map(_.signature)

    def lastBlockHeader: Option[SignedBlockHeader] = blockchain.blockHeader(blockchain.height)
    def lastBlockId: Option[ByteStr]               = lastBlockHeader.map(_.signature)
    def lastBlockTimestamp: Option[Long]           = lastBlockHeader.map(_.header.timestamp)
    def lastBlockIds(howMany: Int): Seq[ByteStr]   = (blockchain.height to blockchain.height - howMany by -1).flatMap(blockId)

    def resolveAlias(aoa: AddressOrAlias): Either[ValidationError, Address] =
      aoa match {
        case a: Address => Right(a)
        case a: Alias   => blockchain.resolveAlias(a)
      }

    def canCreateAlias(alias: Alias): Boolean = blockchain.resolveAlias(alias) match {
      case Left(AliasDoesNotExist(_)) => true
      case _                          => false
    }

    def effectiveBalance(address: Address, confirmations: Int, block: BlockId = blockchain.lastBlockId.getOrElse(ByteStr.empty)): Long = {
      val blockHeight = blockchain.heightOf(block).getOrElse(blockchain.height)
      val bottomLimit = (blockHeight - confirmations + 1).max(1).min(blockHeight)
      val balances    = blockchain.balanceSnapshots(address, bottomLimit, block)
      if (balances.isEmpty) 0L else balances.view.map(_.effectiveBalance).min
    }

    def balance(address: Address, atHeight: Int, confirmations: Int): Long = {
      val bottomLimit = (atHeight - confirmations + 1).max(1).min(atHeight)
      val signature   = blockchain.blockHeader(atHeight).getOrElse(throw new IllegalArgumentException(s"Invalid block height: $atHeight")).signature
      val balances    = blockchain.balanceSnapshots(address, bottomLimit, signature)
      if (balances.isEmpty) 0L else balances.view.map(_.regularBalance).min
    }

    def unsafeHeightOf(id: ByteStr): Int =
      blockchain
        .heightOf(id)
        .getOrElse(throw new IllegalStateException(s"Can't find a block: $id"))

    def wavesPortfolio(address: Address): Portfolio = Portfolio(
      blockchain.balance(address),
      blockchain.leaseBalance(address),
      Map.empty
    )

    def isMiningAllowed(height: Int, effectiveBalance: Long): Boolean =
      GeneratingBalanceProvider.isMiningAllowed(blockchain, height, effectiveBalance)

    def isEffectiveBalanceValid(height: Int, block: Block, effectiveBalance: Long): Boolean =
      GeneratingBalanceProvider.isEffectiveBalanceValid(blockchain, height, block, effectiveBalance)

    def generatingBalance(account: Address, blockId: BlockId = ByteStr.empty): Long =
      GeneratingBalanceProvider.balance(blockchain, account, blockId)

    def allActiveLeases: Seq[LeaseTransaction] = blockchain.collectActiveLeases(_ => true)

    def lastBlockReward: Option[Long] = blockchain.blockReward(blockchain.height)

    def hasAssetScript(asset: IssuedAsset): Boolean = blockchain.assetScript(asset).isDefined
  }
}
