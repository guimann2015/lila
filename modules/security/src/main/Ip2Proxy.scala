package lila.security

import com.github.blemale.scaffeine.AsyncLoadingCache
import play.api.libs.json.*
import play.api.libs.ws.JsonBodyReadables.*
import play.api.libs.ws.StandaloneWSClient

import lila.common.IpAddress

trait Ip2Proxy:

  def apply(ip: IpAddress): Fu[IsProxy]

  def keepProxies(ips: Seq[IpAddress]): Fu[Map[IpAddress, String]]

opaque type IsProxy = String
object IsProxy extends OpaqueString[IsProxy]:
  extension (a: IsProxy)
    def is   = a.value.nonEmpty
    def name = a.value.nonEmpty option a.value
  def unapply(a: IsProxy): Option[String] = a.name
  val empty                               = IsProxy("")

final class Ip2ProxySkip extends Ip2Proxy:

  def apply(ip: IpAddress): Fu[IsProxy] = fuccess(IsProxy.empty)

  def keepProxies(ips: Seq[IpAddress]): Fu[Map[IpAddress, String]] = fuccess(Map.empty)

final class Ip2ProxyServer(
    ws: StandaloneWSClient,
    cacheApi: lila.memo.CacheApi,
    checkUrl: String
)(using Executor, Scheduler)
    extends Ip2Proxy:

  def apply(ip: IpAddress): Fu[IsProxy] = cache.get(ip.value)

  def keepProxies(ips: Seq[IpAddress]): Fu[Map[IpAddress, String]] =
    batch(ips)
      .map:
        _.view
          .zip(ips)
          .collect { case (IsProxy(name), ip) => ip -> name }
          .toMap
      .recover { case e: Exception =>
        logger.warn(s"Ip2Proxy $ips", e)
        Map.empty
      }

  private def batch(ips: Seq[IpAddress]): Fu[Seq[IsProxy]] =
    ips.distinct.take(50) match // 50 * ipv6 length < max url length
      case Nil     => fuccess(Seq.empty[IsProxy])
      case Seq(ip) => apply(ip).dmap(Seq(_))
      case ips =>
        ips.flatMap(ip => cache.getIfPresent(ip.value)).parallel flatMap { cached =>
          if cached.sizeIs == ips.size then fuccess(cached)
          else
            ws.url(s"$checkUrl/batch")
              .addQueryStringParameters("ips" -> ips.mkString(","))
              .get()
              .withTimeout(3 seconds, "Ip2Proxy.batch")
              .map:
                _.body[JsValue].asOpt[Seq[JsObject]] so {
                  _.map(readProxyName)
                }
              .flatMap: res =>
                if res.sizeIs == ips.size then fuccess(res)
                else fufail(s"Ip2Proxy missing results for $ips -> $res")
              .addEffect:
                _.zip(ips).foreach: (proxy, ip) =>
                  cache.put(ip.value, fuccess(proxy))
                  lila.mon.security.proxy.result(proxy.name).increment()
        }

  private val cache = cacheApi[String, IsProxy](32_768, "ip2proxy.ip"):
    _.expireAfterWrite(1 hour).buildAsyncFuture: ip =>
      ws
        .url(checkUrl)
        .addQueryStringParameters("ip" -> ip)
        .get()
        .withTimeout(150.millis, "Ip2Proxy.fetch")
        .dmap(_.body[JsValue])
        .dmap(readProxyName)
        .monSuccess(_.security.proxy.request)
        .addEffect: result =>
          lila.mon.security.proxy.result(result.name).increment()
        .recoverDefault(IsProxy.empty)

  private def readProxyName(js: JsValue): IsProxy = IsProxy:
    (js \ "proxy_type").asOpt[String].filter(_ != "-") | ""
