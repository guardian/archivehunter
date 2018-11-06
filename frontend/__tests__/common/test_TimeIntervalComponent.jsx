import React from 'react';
import {shallow,mount} from 'enzyme';
import TimeIntervalComponent from '../../app/common/TimeIntervalComponent.jsx';
import sinon from 'sinon';
import assert from 'assert';

describe("TimeIntervalComponent", ()=> {
    it("should render seconds only", () => {
        const rendered = shallow(<TimeIntervalComponent editable={false} value={32}/>);
        expect(rendered.find('span.duration').text()).toEqual("32 seconds");
    });

    it("should render minutes and seconds", () => {
        const rendered = shallow(<TimeIntervalComponent editable={false} value={125}/>);
        expect(rendered.find('span.duration').text()).toEqual("2 minutes, 5 seconds");
    });

    it("should render hours, minutes and seconds", () => {
        const rendered = shallow(<TimeIntervalComponent editable={false} value={3725}/>);
        expect(rendered.find('span.duration').text()).toEqual("1 hour, 2 minutes, 5 seconds");
    });

    it("should render singular minutes", () => {
        const rendered = shallow(<TimeIntervalComponent editable={false} value={65}/>);
        expect(rendered.find('span.duration').text()).toEqual("1 minute, 5 seconds");
    });

    it("should render hours, and minutes only", () => {
        const rendered = shallow(<TimeIntervalComponent editable={false} value={3660}/>);
        expect(rendered.find('span.duration').text()).toEqual("1 hour, 1 minute");
    });

    it("should render hours only", () => {
        const rendered = shallow(<TimeIntervalComponent editable={false} value={7200}/>);
        expect(rendered.find('span.duration').text()).toEqual("2 hours");
    });
});