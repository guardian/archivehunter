import React from 'react';
import PropTypes from 'prop-types';

class FileSizeView extends React.Component {
    static propTypes = {
        rawSize: PropTypes.number.isRequired,
        precision: PropTypes.number,  //how many significant figures to round to.
        rateUnits: PropTypes.bool       //if true show kbit/s rather than Kb etc.
    };

    constructor(props){
        super(props);
    }

    countThousands(num, iteration){
        if(num>1000){
            return this.countThousands(num/1000,iteration+1)
        } else {
            return {
                value: num,
                thousands: iteration
            }
        }
    }

    getPostfix(thousands){
        const postfixes = this.props.rateUnits ?
            ["bps", "Kbit/s", "Mbit/s", "Gbit/s", "Tbit/s", "Ebit/s"]
            : ["bytes", "Kb", "Mb", "Gb", "Tb", "Pb", "Eb"];

        if(thousands>postfixes.length) return "ridiculous";
        return postfixes[thousands];
    }

    /**
     * round a number to given precision, but remove any trailing zeroes for a nicer display
     * @param num number to round
     * @param precision number of significant figures to retain
     * @returns {*} rounded number
     */
    withPrecision(num, precision){
        if(precision===0) return num;
        const rounded = num.toPrecision(precision);
        const asString = rounded.toString();
        if(asString.endsWith("0") || asString.endsWith(".")) return this.withPrecision(num, precision-1);
        return rounded;
    }

    render(){
        const actualPrecision = this.props.precision ? this.props.precision : 3;
        const result = this.countThousands(this.props.rawSize, 0);
        return <span className="file-size">{this.withPrecision(result.value, actualPrecision)} {this.getPostfix(result.thousands)}</span>
    }
}

export default FileSizeView;