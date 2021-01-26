import React from 'react';
import PropTypes from 'prop-types';
import moment from 'moment';
import 'moment-duration-format';
import {withStyles, createStyles, TextField, Typography} from "@material-ui/core";

const styles = (theme) => createStyles({
    timeInterval: {
        marginLeft: "1em",
        width: "3em"
    }
});

class TimeIntervalComponent extends React.Component {
    static propTypes = {
        editable: PropTypes.bool.isRequired,
        value: PropTypes.number.isRequired,
        didUpdate: PropTypes.func,
        classes: PropTypes.object
    };

    constructor(props){
        super(props);

        this.state = {
            daysSet: 0,
            hoursSet: 0,
            minutesSet: 0,
            secondsSet:0,
            moment: null
        };

        this.notifyParent = this.notifyParent.bind(this);
    }

    componentDidMount(){
        const d = moment.duration(this.props.value,"seconds");
        this.setState({moment: d, daysSet: d.days(), hoursSet: d.hours(), minutesSet: d.minutes(), secondsSet: d.seconds()})
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
        if(this.props.didUpdate) this.props.didUpdate(this.state.daysSet*(3600*24)+this.state.hoursSet*3600+this.state.minutesSet*60+this.state.secondsSet);
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
                <TextField className="time-interval"
                       type="number"
                       value={this.state.daysSet}
                       onChange={evt=>this.safeUpdateValue("daysSet", evt.target.value)}/> days
                <TextField className="time-interval"
                       type="number"
                       value={this.state.hoursSet}
                       onChange={evt=>this.safeUpdateValue("hoursSet", evt.target.value)}/> hours
                <TextField className="time-interval"
                       type="number"
                       value={this.state.minutesSet}
                       onChange={evt=>this.safeUpdateValue("minutesSet", evt.target.value)}/> minutes
                <TextField className="time-interval"
                       type="number"
                       value={this.state.secondsSet}
                       onChange={evt=>this.safeUpdateValue("secondsSet", evt.target.value)}/> seconds
            </span>
        } else {
            let fmtStringParts = [];
            if (this.state.daysSet > 0) fmtStringParts = fmtStringParts.concat(["d [days]"]);
            if (this.state.hoursSet > 0) fmtStringParts = fmtStringParts.concat(["h [hours]"]);
            if (this.state.minutesSet > 0) fmtStringParts = fmtStringParts.concat(["m [minutes]"]);
            if (this.state.secondsSet > 0) fmtStringParts = fmtStringParts.concat(["s [seconds]"]);
            if(fmtStringParts.length===0){
                return <Typography className="duration">invalid duration</Typography>
            } else {
                const formatString = fmtStringParts.join(", ");

                return <Typography className="duration">{this.state.moment.format(formatString)}</Typography>
            }
        }
    }
}

export default withStyles(styles)(TimeIntervalComponent);