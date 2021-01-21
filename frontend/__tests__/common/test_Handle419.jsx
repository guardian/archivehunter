// import {handle419, setupInterceptor, rejectedCallback} from "../../app/common/Handle419.jsx";
// import sinon from "sinon";
// import * as pandasess from "panda-session";
// import axios from "axios";
//
describe("handle419", ()=>{
    it("is temporarily disabled", ()=>{

    });
});
//     it("should resolve immediately with false if the error in question is not 419", (done)=>{
//         handle419({response:{status:500}}).then(result=>{
//             expect(result).toBeFalsy();
//             done();
//         }).catch(err=>{
//             done.fail(err);
//         });
//     });
//
//     it("should resolve with true if the session refresh resolved correctly", (done)=>{
//         sinon.stub(pandasess, "reEstablishSession").returns(new Promise((resolve, reject)=>resolve()));
//
//         handle419({response:{status:419}}).then(result=>{
//             expect(result).toBeTruthy();
//             done();
//         }).catch(err=>{
//             done.fail(err);
//         })
//     });
//
//     it("should re-throw an error that is emitted by the panda-session lib", (done)=>{
//         pandasess.reEstablishSession.restore();
//         sinon.stub(pandasess, "reEstablishSession").returns(new Promise((resolve, reject)=>reject("kaboom")));
//
//         handle419({response:{status:419}}).then(result=>{
//             done.fail("call should have failed");
//         }).catch(err=>{
//             expect(err).toEqual("Could not re-establish session");
//             done();
//         })
//     })
// });
//
// describe("rejectedCallback", ()=>{
//     it("should re-try the original call if handle419 succeeds", (done)=>{
//         //'updated JSDOM does not allow easy stubbing of window.location so removing that part of the test for now
//         pandasess.reEstablishSession.restore();
//         sinon.stub(pandasess, "reEstablishSession").returns(new Promise((resolve, reject)=>resolve()));
//         sinon.stub(axios, "request").returns(Promise.resolve("request made"));
//         const fakeErr = {
//             config: "original request here",
//             response: {
//                 status: 419
//             }
//         };
//         rejectedCallback(fakeErr).then(result=>{
//             expect(axios.request.calledWith("original request here")).toBeTruthy();
//             axios.request.restore();
//             expect(result).toEqual("request made");
//             done();
//         }).catch(err=>{
//             try {
//                 axios.request.restore();
//             } catch(err){
//                 console.warn(err);
//             }
//             done.fail(err);
//         })
//     });
//
//     it("should reload the page if reEstablishSession fails", (done)=>{
//         //'updated JSDOM does not allow easy stubbing of window.location so removing that part of the test for now
//         pandasess.reEstablishSession.restore();
//         sinon.stub(pandasess, "reEstablishSession").returns(new Promise((resolve, reject)=>reject("something went splat")));
//         sinon.stub(axios, "request").returns(Promise.resolve("request made"));
//         const fakeErr = {
//             config: "original request here",
//             response: {
//                 status: 419
//             }
//         };
//         rejectedCallback(fakeErr).then(result=>{
//             expect(axios.request.called).toBeFalsy();
//             axios.request.restore();
//             done();
//         }).catch(err=>{
//             try {
//                 axios.request.restore();
//             } catch(err){
//                 console.warn(err);
//             }
//             done.fail(err);
//         })
//     });
//
//     it("should immediately reject if the error is not 419", (done)=>{
//         //'updated JSDOM does not allow easy stubbing of window.location so removing that part of the test for now
//         pandasess.reEstablishSession.restore();
//         sinon.stub(pandasess, "reEstablishSession").returns(new Promise((resolve, reject)=>reject("something went splat")));
//         sinon.stub(axios, "request").returns(Promise.resolve("request made"));
//         const fakeErr = {
//             config: "original request here",
//             response: {
//                 status: 500
//             }
//         };
//         rejectedCallback(fakeErr).then(result=>{
//             try {
//                 axios.request.restore();
//             } catch(err){
//                 console.warn(err);
//             }
//             done.fail("callback promise should have been rejected");
//         }).catch(err=>{
//             axios.request.restore();
//             expect(err).toEqual(fakeErr);
//             done();
//         })
//     });
// });