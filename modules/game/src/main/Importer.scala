package lila.game
package importer

import chess.format.Fen
import chess.format.pgn.{ ParsedPgn, Parser, PgnStr, Reader, Sans }
import chess.{ ByColor, Color, ErrorStr, Mode, Outcome, Replay, Status }
import scala.util.chaining.*

import lila.game.GameExt.finish
import lila.core.game.{ Game, NewGame, Player }
import lila.tree.{ ImportReady, ImportReady2 }

private val maxPlies = 600

final class Importer(gameRepo: lila.core.game.GameRepo)(using Executor) extends lila.tree.Importer:

  def importAsGame(pgn: PgnStr, forceId: Option[GameId] = none)(using me: Option[MyId]): Fu[Game] =
    import lila.db.dsl.{ *, given }
    import lila.core.game.BSONFields as F
    import gameRepo.gameHandler
    gameRepo.coll
      .one[Game]($doc(s"${F.pgnImport}.h" -> lila.game.PgnImport.hash(pgn)))
      .flatMap:
        case Some(game) => fuccess(game)
        case None =>
          for
            g <- parseImport(pgn, me).toFuture
            game = forceId.fold(g.game.sloppy)(g.game.withId)
            _ <- gameRepo.insertDenormalized(game, initialFen = g.initialFen)
            _ <- game.pgnImport.flatMap(_.user).isDefined.so {
              // import date, used to make a compound sparse index with the user
              gameRepo.coll.updateField($id(game.id), s"${F.pgnImport}.ca", game.createdAt).void
            }
            _ <- gameRepo.finish(game.id, game.winnerColor, None, game.status)
          yield game

val parseImport: (PgnStr, Option[UserId]) => Either[ErrorStr, ImportReady] = (pgn, user) =>
  lila.tree.parseImport(pgn).map { case ImportReady2(game, result, replay, initialFen, parsed) =>
    val dbGame = lila.core.game
      .newGame(
        chess = game,
        players = ByColor: c =>
          lila.game.Player.makeImported(c, parsed.tags.names(c), parsed.tags.elos(c)),
        mode = Mode.Casual,
        source = lila.core.game.Source.Import,
        pgnImport = PgnImport.make(user = user, date = parsed.tags.anyDate, pgn = pgn).some
      )
      .sloppy
      .start
      .pipe: dbGame =>
        result.fold(dbGame)(res => dbGame.finish(res.status, res.winner))
    ImportReady(NewGame(dbGame), initialFen)
  }
