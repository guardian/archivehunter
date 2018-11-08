import React from 'react';
import PropTypes from 'prop-types';
import moment from 'moment';
import 'moment-duration-format';

class TimeIntervalComponent extends React.Component {
    static propTypes = {
        editable: PropTypes.bool.isRequired,
        value: PropTypes.number.isRequired,
        didUpdate: PropTypes.func
    };

    constructor(props){
        super(props);

        this.state = {
            hoursSet: 0,
            minutesSet: 0,
            secondsSet:0,
            moment: null
        };

        this.notifyParent = this.notifyParent.bind(this);
    }

    componentWillMount(){
        const d = moment.duration(this.props.value,"seconds");
        this.setState({moment: d, hoursSet: d.hours(), minutesSet: d.minutes(), secondsSet: d.seconds()})
    }

    componentDidUpdate(oldProps,oldState){
        if(oldProps.value!==this.props.value){
            console.log("time interval seconds updated from ", oldProps.value, " to ", this.props.value);
            const d = moment.duration(this.props.value,"seconds");
            this.setState({moment: d, hoursSet: d.hours(), minutesSet: d.minutes(), secondsSet: d.seconds()});
            console.log(d);
        }
    }

    notifyParent(){
        console.log("notifyParent", this.state);
        if(this.props.didUpdate) this.props.didUpdate(this.state.hoursSet*3600+this.state.minutesSet*60+this.state.secondsSet);
    }

    safeUpdateValue(key, newValue){
        let update = {};
        update[key] = parseInt(newValue);
        console.log("new value for " + key, newValue);
        if(!isNaN(update[key])) //only set the update if the number is valid
            this.setState(update,this.notifyParent)
    }

    render(){
        if(this.props.editable){
            return <span className="duration">
                <input className="time-interval"
                       type="number"
                       value={this.state.hoursSet}
                       onChange={evt=>this.safeUpdateValue("hoursSet", evt.target.value)}/> hours
                <input className="time-interval"
                       type="number"
                       value={this.state.minutesSet}
                       onChange={evt=>this.safeUpdateValue("minutesSet", evt.target.value)}/> minutes
                <input className="time-interval"
                       type="number"
                       value={this.state.secondsSet}
                       onChange={evt=>this.safeUpdateValue("secondsSet", evt.target.value)}/> seconds
            </span>
        } else {
            let fmtStringParts = [];
            if (this.state.hoursSet > 0) fmtStringParts = fmtStringParts.concat(["h [hours]"]);
            if (this.state.minutesSet > 0) fmtStringParts = fmtStringParts.concat(["m [minutes]"]);
            if (this.state.secondsSet > 0) fmtStringParts = fmtStringParts.concat(["s [seconds]"]);
            const formatString = fmtStringParts.join(", ");

            return <span className="duration">{this.state.moment.format(formatString)}</span>
        }
    }
}

export default TimeIntervalComponent;