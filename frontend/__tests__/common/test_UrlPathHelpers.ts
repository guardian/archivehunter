import {urlParamsFromSearch} from "../../app/common/UrlPathHelpers";

describe("UrlPathHelpers", ()=>{
    it("must split a provided string into key-value pairs", ()=>{
        const result = urlParamsFromSearch("?q1=somevalue&q2=someothervalue");

        expect(result.hasOwnProperty("q1")).toBeTruthy();
        expect(result.hasOwnProperty("q2")).toBeTruthy();
        expect(Object.keys(result).length).toEqual(2);
        expect(result.q1).toEqual("somevalue");
        expect(result.q2).toEqual("someothervalue");
    });

    it("must work without a leading ?", ()=>{
        const result = urlParamsFromSearch("q1=somevalue&q2=someothervalue");

        expect(result.hasOwnProperty("q1")).toBeTruthy();
        expect(result.hasOwnProperty("q2")).toBeTruthy();
        expect(Object.keys(result).length).toEqual(2);
        expect(result.q1).toEqual("somevalue");
        expect(result.q2).toEqual("someothervalue");
    });

    it("must work with an empty string", ()=>{
        const result = urlParamsFromSearch("");
        expect(Object.keys(result).length).toEqual(0);
    })
})