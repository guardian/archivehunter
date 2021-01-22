import React from 'react';
import {shallow,mount} from 'enzyme';
import sinon from 'sinon';
import BrowseComponent from '../../app/browse/BrowseComponent.jsx';
import moxios from "moxios";

describe("BrowseComponent.loadNextNodeOfSpecific", ()=>{
    const sampleTree = {
        children: [
            {
                name: "path",
                children: [
                    {
                        name: "to",
                        children: [
                            {
                                name: "my",
                                children: [
                                    {
                                        name: "files",
                                        children: []
                                    }
                                ]
                            },
                            {
                                name: "your",
                                children: [
                                    {
                                        name: "stuff",
                                        children: []
                                    }
                                ]
                            }
                        ]
                    },
                    {
                        name: "of",
                        children: [
                            {
                                name: "something",
                                children: [
                                    {
                                        name: "else",
                                        children: []
                                    }
                                ]
                            }
                        ]
                    }
                ]
            }
        ]
    };

    beforeEach(()=>{
        moxios.install();
    })

    afterEach(()=>{
        moxios.uninstall();
    });

    it("should asynchronously call loadSubFolder for each part of the path", (done)=>{
        const rendered = shallow(<BrowseComponent location={{ }}/>);

        rendered.instance().loadSubFolder = sinon.stub().returns(new Promise((resolve,reject)=>resolve()));

        rendered.instance().loadNextNodeOfSpecific(sampleTree,["path","to","your","stuff"],0).then(terminalNode=>{

            try {
                expect(terminalNode).toEqual({
                    name: "stuff",
                    children: []
                });

                sinon.assert.callCount(rendered.instance().loadSubFolder, 4);

                done();
            } catch(ex){
                done.fail(ex);
            }
        }).catch(err=>done.fail(err))
    });

    it("should error by rejecting the promise if a requested node is not present in the tree", (done)=>{
        const rendered = shallow(<BrowseComponent location={{ }}/>);

        rendered.instance().loadSubFolder = sinon.stub().returns(new Promise((resolve,reject)=>resolve()));

        rendered.instance().loadNextNodeOfSpecific(sampleTree,["path","to","Jonathans","stuff"],0).then(terminalNode=>{
            done.fail("not expecting this to succeed")
        }).catch(err=>{
            expect(err).toEqual("Could not find relevant child node");
            done();
        })
    })
});

describe("BrowseComponent.loadSpecificTreePath", ()=>{
    it("should call loadNextNodeOfSpecific if state.openedPath is valid, then call triggerSearch on the terminal node, then resolve", done=>{
        const rendered = shallow(<BrowseComponent location={{ }}/>);
        rendered.instance().triggerSearch = sinon.stub().resolves();
        rendered.instance().loadNextNodeOfSpecific = sinon.stub().resolves({name: "somenode", children: []});

        rendered.instance().state.openedPath = ["path","to","something"];
        rendered.instance().state.treeContents = ["faketreecontents"];

        rendered.instance().loadSpecificTreePath().then(result=>{
            try {
                sinon.assert.calledWith(rendered.instance().loadNextNodeOfSpecific, {children: ["faketreecontents"]}, ["path", "to", "something"], 0);
                sinon.assert.calledWith(rendered.instance().triggerSearch, {name: "somenode", children: []});
                done();
            }catch (err){
                done.fail(err);
            }
        }).catch(err=>done.fail(err));
    });

    it("should stop if loadNextNodeOfSpecific fails", done=>{
        const rendered = shallow(<BrowseComponent location={{ }}/>);
        rendered.instance().triggerSearch = sinon.stub().resolves();
        rendered.instance().loadNextNodeOfSpecific = sinon.stub().rejects("test");

        rendered.instance().state.openedPath = ["path","to","something"];
        rendered.instance().state.treeContents = ["faketreecontents"];

        rendered.instance().loadSpecificTreePath().then(result=>{
            done.fail("should have raised an exception");
        }).catch(err=>{
            try {
                sinon.assert.calledWith(rendered.instance().loadNextNodeOfSpecific, {children: ["faketreecontents"]}, ["path", "to", "something"], 0);
                sinon.assert.notCalled(rendered.instance().triggerSearch);

                done();
            }catch (err){
                done.fail(err);
            }
        });
    });

    it("should fail the promise if triggerSearch fails", done=>{
        const rendered = shallow(<BrowseComponent location={{ }}/>);
        rendered.instance().triggerSearch = sinon.stub().rejects();
        rendered.instance().loadNextNodeOfSpecific = sinon.stub().resolves({name: "somenode", children: []});

        rendered.instance().state.openedPath = ["path","to","something"];
        rendered.instance().state.treeContents = ["faketreecontents"];

        rendered.instance().loadSpecificTreePath().then(result=>{
            done.fail("should have raised an exception");
        }).catch(err=>{
            try {
                sinon.assert.calledWith(rendered.instance().loadNextNodeOfSpecific, {children: ["faketreecontents"]}, ["path", "to", "something"], 0);
                sinon.assert.calledWith(rendered.instance().triggerSearch, {name: "somenode", children: []});
                done();
            }catch (err){
                done.fail(err);
            }
        });
    });

    it("should call triggerSearch with no arguments if state.openedPath is not valid", done=>{
        const rendered = shallow(<BrowseComponent location={{ }}/>);
        rendered.instance().triggerSearch = sinon.stub().resolves();

        rendered.instance().loadNextNodeOfSpecific = sinon.stub().resolves({name: "somenode", children: []});

        rendered.instance().state.openedPath = null;
        rendered.instance().state.treeContents = ["faketreecontents"];

        rendered.instance().loadSpecificTreePath().then(result=>{
            sinon.assert.notCalled(rendered.instance().loadNextNodeOfSpecific);
            sinon.assert.calledWith(rendered.instance().triggerSearch);
            done();
        }).catch(err=>done.fail(err));
    })
});