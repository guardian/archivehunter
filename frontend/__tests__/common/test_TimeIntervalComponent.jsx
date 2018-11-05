import React from 'react';
import {shallow,mount} from 'enzyme';
import TimeIntervalComponent from '../../app/common/TimeIntervalComponent.jsx';
import sinon from 'sinon';
import assert from 'assert';

describe("TimeIntervalComponent", ()=> {
    it("should display the time provided to it", () => {
        const rendered = shallow(<TimeIntervalComponent editable={false} value={32}/>);
        expect(rendered.find('span.duration').text()).toEqual("0:32");
    });
});