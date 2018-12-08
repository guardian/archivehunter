import SearchManager from "../../app/SearchManager/SearchManager.jsx";
import sinon from 'sinon';

describe("SearchManager.generateId", ()=>{
    it("should generate a uuid", ()=>{
        const mgr = new SearchManager();
        const result = mgr.generateId();
        expect(result.match(/^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i)).toBeTruthy();
    });
});

describe("SearchManager.makeNewSearch", ()=>{
    it("should create a new search object and start it, returning the uuid", (done)=>{
        const mgr = new SearchManager();
        mgr.generateId = sinon.stub();
        mgr.generateId.onCall(0).returns("fake-id");

        const fakeSearcher = sinon.spy();
        fakeSearcher.startSearch = sinon.spy();
        fakeSearcher.searchId = "fake-id";

        mgr._buildSearch = sinon.stub();
        mgr._buildSearch.onCall(0).returns(fakeSearcher);

        expect(mgr.currentSearch).toEqual(null);

        mgr.makeNewSearch("GET","/path/to/url",null,null,100,null,null,null,null).then(searchId=> {
            expect(mgr.currentSearch).toEqual(fakeSearcher);

            expect(fakeSearcher.startSearch.calledWith()).toBeTruthy();
            expect(searchId).toEqual("fake-id");
            done();
        });
    });

    it("should cancel any pre-existing search and wait until it returns before starting the next", (done)=>{
        const mgr = new SearchManager();
        mgr.generateId = sinon.stub();
        mgr.generateId.onCall(0).returns("fake-id");

        const afterCancelPromise = new Promise((resolve,reject)=>resolve());

        const fakeOldSearcher = sinon.spy();
        fakeOldSearcher.searchId = "old-search";
        fakeOldSearcher.startSearch = sinon.spy();
        fakeOldSearcher.cancel = sinon.stub();
        fakeOldSearcher.cancel.onCall(0).returns(afterCancelPromise);

        const fakeNewSearcher = sinon.spy();
        fakeNewSearcher.startSearch = sinon.spy();
        fakeNewSearcher.searchId = "new-search";

        mgr._buildSearch = sinon.stub();
        mgr._buildSearch.onCall(0).returns(fakeNewSearcher);
        mgr.currentSearch = fakeOldSearcher;

        const result = mgr.makeNewSearch("GET","/path/to/url",null,null,100,null,null,null,null);

        result.then(searchId=> {
            expect(fakeOldSearcher.cancel.called).toBeTruthy();
            expect(fakeNewSearcher.startSearch.called).toBeTruthy();
            expect(searchId).toEqual("new-search");
            console.log(mgr.currentSearch.searchId);
            expect(mgr.currentSearch).toEqual(fakeNewSearcher);
            done();
        });

    })
});