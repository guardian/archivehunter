package responses

import com.sksamuel.elastic4s.http.search.{SuggestionResult, TermSuggestionResult}

object BasicSuggestionsResponse extends ((String,Seq[String])=>BasicSuggestionsResponse){
  def fromEsResponse(resp:Map[String,TermSuggestionResult]):BasicSuggestionsResponse = {
    //val opts=resp.values.foldLeft[Seq[SuggestionResult]](Seq())((acc,entry)=>acc++entry).map(_.text)
    val opts = resp.values.foldLeft[Seq[TermSuggestionResult]](Seq())((acc,entry)=>acc++Seq(entry)).flatMap(_.optionsText)

    new BasicSuggestionsResponse("ok",opts)
  }
}

case class BasicSuggestionsResponse(status:String,suggestions:Seq[String])
