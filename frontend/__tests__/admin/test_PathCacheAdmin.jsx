import React from "react";
import {mount, shallow} from "enzyme";
import moxios from "moxios";
import PathCacheAdmin from "../../app/admin/PathCacheAdmin";

describe("PathCacheAdmin", ()=>{
    beforeEach(()=>{
        moxios.install();
    });

    afterEach(()=>{
        moxios.uninstall();
    });

    it("should mount", ()=>{
        const mockLocation = {pathname:""}
        const rendered = mount(<PathCacheAdmin location={mockLocation}/>)
    })
})