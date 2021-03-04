import React from "react";
import moxios from "moxios";
import sinon from "sinon";
import {mount} from "enzyme";
import MediaPlayer from "../../app/Entry/MediaPlayer";
import {act} from "react-dom/test-utils";

describe("MediaPlayer", ()=>{
    beforeEach(()=>moxios.install());
    afterEach(()=>moxios.uninstall());

    it("should invoke the onProxyNotFound callback if loading returns a 404", (done)=>{
        const mockProxyNotFound = sinon.spy();
        const mockError = sinon.spy();

        const rendered = mount(<MediaPlayer entryId="abcdefg"
                                            playableType="VIDEO"
                                            autoPlay={false}
                                            onError={mockError}
                                            onProxyNotFound={mockProxyNotFound}/>
                                            )

        moxios.wait(async ()=>{
            try {
                const rq = moxios.requests.mostRecent();
                expect(rq.url).toEqual(`/api/proxy/abcdefg/playable?proxyType=VIDEO`);
                await rq.respondWith({
                    status: 404,
                    response: {
                        status: "not_found",
                        detail: "no Some(VIDEO) proxy found for abcdefg"
                    }
                });

                expect(mockError.callCount).toEqual(0);
                expect(mockProxyNotFound.callCount).toEqual(1);
                expect(mockProxyNotFound.calledWith("VIDEO")).toBeTruthy();
                done();
            } catch(err) {
                done.fail(err);
            }
        });
    });

    it("should invoke the onError callback if loading returns a 500", (done)=>{
        const mockProxyNotFound = sinon.spy();
        const mockError = sinon.spy();

        const rendered = mount(<MediaPlayer entryId="abcdefg"
                                            playableType="VIDEO"
                                            autoPlay={false}
                                            onError={mockError}
                                            onProxyNotFound={mockProxyNotFound}/>
        )

        moxios.wait(async ()=>{
            try {
                const rq = moxios.requests.mostRecent();
                expect(rq.url).toEqual(`/api/proxy/abcdefg/playable?proxyType=VIDEO`);
                await rq.respondWith({
                    status: 500,
                    response: {
                        status: "error",
                        detail: "kaboom"
                    }
                });

                expect(mockError.callCount).toEqual(1);
                expect(mockProxyNotFound.callCount).toEqual(0);
                done();
            } catch(err) {
                done.fail(err);
            }
        });
    });

    it("should prepare a video player for a video proxy", (done)=>{
        const mockProxyNotFound = sinon.spy();
        const mockError = sinon.spy();

        const rendered = mount(<MediaPlayer entryId="abcdefg"
                                            playableType="VIDEO"
                                            autoPlay={false}
                                            onError={mockError}
                                            onProxyNotFound={mockProxyNotFound}/>
        )

        moxios.wait( async ()=>{
            await act(async ()=> {
                try {
                    const rq = moxios.requests.mostRecent();
                    expect(rq.url).toEqual(`/api/proxy/abcdefg/playable?proxyType=VIDEO`);
                    await rq.respondWith({
                        status: 200,
                        response: {
                            status: "ok",
                            uri: "https://some/playable/uri",
                            mimeType: {major: "video", minor: "mp4"}
                        }
                    });

                } catch (err) {
                    done.fail(err);
                }
            });
            expect(mockError.callCount).toEqual(0);
            expect(mockProxyNotFound.callCount).toEqual(0);

            rendered.update();
            const videoPlayer = rendered.find("video");
            console.log(rendered.html());
            expect(videoPlayer.length).toEqual(1);
            expect(videoPlayer.props().src).toEqual("https://some/playable/uri");
            expect(videoPlayer.props().autoPlay).toEqual(false);
            done();
        });
    })
})