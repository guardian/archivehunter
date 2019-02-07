import {handle419} from "../../app/common/Handle419.jsx";
import sinon from "sinon";
import * as pandasess from "panda-session";

describe("handle419", ()=>{
    it("should resolve immediately with false if the error in question is not 419", (done)=>{
        handle419({response:{status:500}}).then(result=>{
            expect(result).toBeFalsy();
            done();
        }).catch(err=>{
            done.fail(err);
        });
    });

    it("should resolve with true if the session refresh resolved correctly", (done)=>{
        sinon.stub(pandasess, "reEstablishSession").returns(new Promise((resolve, reject)=>resolve()));

        handle419({response:{status:419}}).then(result=>{
            expect(result).toBeTruthy();
            done();
        }).catch(err=>{
            done.fail(err);
        })
    });

    it("should re-throw an error that is emitted by the panda-session lib", (done)=>{
        pandasess.reEstablishSession.restore();
        sinon.stub(pandasess, "reEstablishSession").returns(new Promise((resolve, reject)=>reject("kaboom")));

        handle419({response:{status:419}}).then(result=>{
            done.fail("call should have failed");
        }).catch(err=>{
            expect(err).toEqual("kaboom");
            done();
        })
    })
});