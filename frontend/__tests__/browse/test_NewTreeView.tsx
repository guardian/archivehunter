import React from "react";
import moxios from "moxios";
import {loadInPaths, TreeLeaf} from "../../app/browse/NewTreeView";
import {mount} from "enzyme";
import sinon from "sinon";
import {PathEntry} from "../../app/types";
import {act} from "react-dom/test-utils";

describe("loadInPaths", ()=>{
    beforeEach(()=>moxios.install());
    afterEach(()=>moxios.uninstall());

    it("should make a request then parse data into PathEntry objects", (done)=>{
        const resultPromise = loadInPaths("my-collection","some-path");

        moxios.wait(async ()=>{
            const request = moxios.requests.mostRecent();
            expect(request.url).toEqual("/api/browse/my-collection?prefix=some-path");
            try {
                await act(async () => {
                    await request.respondWith({
                        status: 200,
                        response: {
                            status: "ok",
                            entityClass: "directory",
                            entries: [
                                "path/to/directory/content1/",
                                "path/to/directory/content2/",
                                "path/to/directory/content3/",
                            ]
                        }
                    });
                const result = await resultPromise;

                //console.log(result);
                expect(result.length).toEqual(3);

                expect(result[0]).toEqual({name: "content1", fullpath: "path/to/directory/content1/", idx: 0});
                expect(result[1]).toEqual({name: "content2", fullpath: "path/to/directory/content2/", idx: 1});
                expect(result[2]).toEqual({name: "content3", fullpath: "path/to/directory/content3/", idx: 2});
                done();
                });
            } catch(err) {
                done.fail(err);
            }
        })
    })
});

describe("TreeLeaf", ()=>{
    beforeEach(()=>moxios.install());
    afterEach(()=>moxios.uninstall());

    it("should load in data when it is clicked", async (done)=>{
        const leafSelectedSpy = sinon.spy();
        const sampleNode:PathEntry = {
            name: "directory",
            fullpath: "path/to/directory/",
            idx: 0
        }
        const rendered = mount(<TreeLeaf path={sampleNode} leafWasSelected={leafSelectedSpy} collectionName="my-collection" parentKey="0"/>);

        try {
            expect(rendered.find("div.MuiTreeItem-label").text()).toEqual("directory");

            rendered.childAt(0).props().onIconClick();

            moxios.wait(async ()=>{
                const request = moxios.requests.mostRecent();
                expect(request.url).toEqual("/api/browse/my-collection?prefix=path/to/directory/");

                try {
                    await act(async ()=> {
                        await request.respondWith({
                            status: 200,
                            response: {
                                status: "ok",
                                entityClass: "directory",
                                entries: [
                                    "path/to/directory/content1/",
                                    "path/to/directory/content2/",
                                    "path/to/directory/content3/",
                                ]
                            }
                        });
                    });
                    rendered.update();
                    done();
                } catch (err) {
                    done.fail(err);
                }
            })
        } catch(err) {
            done.fail(err);
        }

    })
});