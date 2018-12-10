import Searcher from '../../app/SearchManager/Searcher.jsx';
import moxios from 'moxios';
import sinon from 'sinon';

describe("Searcher.getNextPage", ()=>{
    beforeEach(()=>{
        moxios.install();
    });

    afterEach(()=>{
        moxios.uninstall();
    });

    it("should make the relevant request and call nextPageCb for each downloaded page before calling completedCb when done", (done)=>{
        const nextPageCb = sinon.spy();
        const completedCb = sinon.spy();
        const errorCb = sinon.spy();
        const cancelledCb = sinon.spy();

        const searcher = new Searcher("test1","GET","/path/to/my/endpoint",null,null,10,nextPageCb,completedCb, cancelledCb, errorCb);

        searcher.getNextPage();

        moxios.wait(()=>{
            const request = moxios.requests.mostRecent();
            expect(request.url).toEqual("/path/to/my/endpoint?size=10&startAt=0");
            const response = {
                status: 200,
                response: {
                    status: "ok",
                    entries: ["one","two","three"]
                }
            };

            request.respondWith(response).then(()=>{
                expect(nextPageCb.called).toBeTruthy();
                moxios.wait(()=>{
                    const secondRequest = moxios.requests.mostRecent();
                    expect(secondRequest.url).toEqual("/path/to/my/endpoint?size=10&startAt=10");
                    const secondResponse = {
                        status: 200,
                        response: {
                            status: "ok",
                            entries: []
                        }
                    };
                    secondRequest.respondWith(secondResponse).then(()=>{
                        expect(completedCb.called).toBeTruthy();
                        expect(errorCb.notCalled).toBeTruthy();
                        expect(searcher.operationInProgress).toBeFalsy();
                        done();
                    });
                })
            }).catch(err=>done.fail(err));
        })
    });

    it("should call errorCb if axios returns an error", (done)=>{
        const nextPageCb = sinon.spy();
        const completedCb = sinon.spy();
        const errorCb = sinon.spy();
        const cancelledCb = sinon.spy();

        const searcher = new Searcher("test1","GET","/path/to/my/endpoint",null,null,10,nextPageCb,completedCb, cancelledCb, errorCb);

        searcher.getNextPage();

        moxios.wait(()=>{
            const request = moxios.requests.mostRecent();
            expect(request.url).toEqual("/path/to/my/endpoint?size=10&startAt=0");
            const response = {
                status: 500,
                response: {
                    status: "error",
                    detail: "kaboom"
                }
            };

            request.respondWith(response).then(()=>{
                expect(nextPageCb.called).toBeFalsy();
                expect(errorCb.called).toBeTruthy();
                expect(searcher.operationInProgress).toBeFalsy();
                done();
            }).catch(err=>done.fail(err));
        })
    });

    it("should marshall k/v pairs into a request query string", (done)=>{
        const nextPageCb = sinon.spy();
        const completedCb = sinon.spy();
        const errorCb = sinon.spy();
        const cancelledCb = sinon.spy();

        const searcher = new Searcher("test1","GET","/path/to/my/endpoint",{key1:"value1",key2:"value2"},null,10,nextPageCb,completedCb, cancelledCb, errorCb);

        searcher.getNextPage();

        moxios.wait(()=>{
            const request = moxios.requests.mostRecent();
            expect(request.url).toEqual("/path/to/my/endpoint?size=10&startAt=0&key1=value1&key2=value2");
            const response = {
                status: 200,
                response: {
                    status: "ok",
                    entries: ["one","two","three"]
                }
            };

            request.respondWith(response).then(()=>{
                expect(nextPageCb.called).toBeTruthy();
                moxios.wait(()=>{
                    const secondRequest = moxios.requests.mostRecent();
                    expect(secondRequest.url).toEqual("/path/to/my/endpoint?size=10&startAt=10&key1=value1&key2=value2");
                    const secondResponse = {
                        status: 200,
                        response: {
                            status: "ok",
                            entries: []
                        }
                    };
                    secondRequest.respondWith(secondResponse).then(()=>{
                        expect(completedCb.called).toBeTruthy();
                        expect(errorCb.notCalled).toBeTruthy();
                        expect(searcher.operationInProgress).toBeFalsy();
                        done();
                    });
                })
            }).catch(err=>done.fail(err));
        })
    });

    it("should send a request body if provided", (done)=>{
        const nextPageCb = sinon.spy();
        const completedCb = sinon.spy();
        const errorCb = sinon.spy();
        const cancelledCb = sinon.spy();

        const searcher = new Searcher("test1","GET","/path/to/my/endpoint",null,
            {data: "request body string", contentType: "text/plain"}
            ,10,nextPageCb,completedCb, cancelledCb, errorCb);

        searcher.getNextPage();

        moxios.wait(()=>{
            const request = moxios.requests.mostRecent();
            expect(request.url).toEqual("/path/to/my/endpoint?size=10&startAt=0");
            expect(request.config.data).toEqual("request body string");
            const response = {
                status: 500,
                response: {
                    status: "error",
                    detail: "kaboom"
                }
            };

            request.respondWith(response).then(()=>{
                expect(nextPageCb.called).toBeFalsy();
                expect(errorCb.called).toBeTruthy();
                expect(searcher.operationInProgress).toBeFalsy();
                done();
            }).catch(err=>done.fail(err));
        })
    });
});

describe("Searcher.startSearch", ()=>{
    it("should reset startAt and then trigger getNextPage", ()=>{
        const nextPageStub = sinon.spy();
        const searcher = new Searcher("test1","GET","/path/to/my/endpoint",null,"request body string",10,null,null, null, null);

        searcher.startAt = 256;
        searcher.getNextPage = nextPageStub;
        searcher.startSearch();

        expect(searcher.startAt).toEqual(0);
        expect(nextPageStub.called).toBeTruthy();
    })
});
/* does not look like it's possible to get moxios to simulate cancels - the request simply proceeds as normal :( */
// describe("Searcher.cancel", ()=>{
//     beforeEach(()=>{
//         moxios.install();
//     });
//
//     afterEach(()=>{
//         moxios.uninstall();
//     });
//
//     it("should cancel an ongoing search and call the cancelledCb", (done)=>{
//         const nextPageCb = sinon.spy();
//         const completedCb = sinon.spy();
//         const errorCb = sinon.spy();
//         const cancelledCb = sinon.spy();
//
//         const searcher = new Searcher("test1","GET","/path/to/my/endpoint",null,"request body string",10,nextPageCb,completedCb, cancelledCb, errorCb);
//
//         searcher.getNextPage();
//
//         searcher.cancel("test");
//
//         moxios.wait(()=>{
//             const request = moxios.requests.mostRecent();
//             expect(request.url).toEqual("/path/to/my/endpoint?size=10&startAt=0");
//             const response = {
//                 status: 200,
//                 response: {
//                     entries: ["nothing"]
//                 }
//             };
//
//             request.respondWith(response).then(()=> {
//                 expect(nextPageCb.called).toBeFalsy();
//                 expect(completedCb.called).toBeFalsy();
//                 expect(cancelledCb.called).toBeTruthy();
//                 expect(errorCb.called).toBeFalsy();
//                 done();
//             }).catch(err=>done.fail(err));
//         })
//     })
// });