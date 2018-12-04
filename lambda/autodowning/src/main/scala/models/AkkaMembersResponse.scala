package models

/*
/*
 {
   "selfNode": "akka.tcp://test@10.10.10.10:1111",
   "members": [
     {
       "node": "akka.tcp://test@10.10.10.10:1111",
       "nodeUid": "1116964444",
       "status": "Up",
       "roles": []
     }
   ],
   "unreachable": [],
   "leader: "akka.tcp://test@10.10.10.10:1111",
   "oldest: "akka.tcp://test@10.10.10.10:1111"
 }
 */
 */
case class AkkaMembersResponse (selfNode:String, members:Seq[AkkaMember], unreachable:Seq[AkkaMember], leader:String, oldest:String)

