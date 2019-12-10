package com.wavesplatform.api.http.requests

import com.wavesplatform.account.PublicKey
import com.wavesplatform.lang.ValidationError
import com.wavesplatform.transaction.Asset.{IssuedAsset, Waves}
import com.wavesplatform.transaction.assets.UpdateAssetInfoTransaction
import com.wavesplatform.transaction.{Asset, AssetIdStringLength, Proofs, TxAmount, TxTimestamp, TxVersion}
import play.api.libs.json.{Format, Json}
import cats.implicits._

case class SignedUpdateAssetInfoRequest(
    version: TxVersion,
    chainId: Byte,
    sender: String,
    assetId: String,
    name: String,
    description: String,
    timestamp: TxTimestamp,
    fee: TxAmount,
    feeAssetId: Option[String],
    proofs: Proofs
) {

  def toTx: Either[ValidationError, UpdateAssetInfoTransaction] =
    for {
      _sender  <- PublicKey.fromBase58String(sender)
      _assetId <- parseBase58(assetId, "invalid.assetId", AssetIdStringLength)
      _feeAssetId <- feeAssetId
        .traverse(parseBase58(_, "invalid.assetId", AssetIdStringLength).map(IssuedAsset))
        .map(_ getOrElse Waves)
      tx <- UpdateAssetInfoTransaction
        .create(version, chainId, _sender, _assetId, name, description, timestamp, fee, _feeAssetId, proofs)
    } yield tx

}

object SignedUpdateAssetInfoRequest {
  implicit val format: Format[SignedUpdateAssetInfoRequest] = Json.format
}